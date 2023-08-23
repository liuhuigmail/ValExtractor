package com.google.javascript.jscomp;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Charsets;
import com.google.common.base.Preconditions;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.google.common.collect.Sets;
import com.google.common.io.Files;
import com.google.javascript.jscomp.AbstractCompiler.LifeCycleStage;
import com.google.javascript.jscomp.CompilerOptions.LanguageMode;
import com.google.javascript.jscomp.ExtractPrototypeMemberDeclarations.Pattern;
import com.google.javascript.jscomp.NodeTraversal.Callback;
import com.google.javascript.jscomp.parsing.ParserRunner;
import com.google.javascript.rhino.IR;
import com.google.javascript.rhino.Node;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class DefaultPassConfig extends PassConfig  {
  final private static String COMPILED_CONSTANT_NAME = "COMPILED";
  final private static String CLOSURE_LOCALE_CONSTANT_NAME = "goog.LOCALE";
  final static DiagnosticType TIGHTEN_TYPES_WITHOUT_TYPE_CHECK = DiagnosticType.error("JSC_TIGHTEN_TYPES_WITHOUT_TYPE_CHECK", "TightenTypes requires type checking. Please use --check_types.");
  final static DiagnosticType CANNOT_USE_PROTOTYPE_AND_VAR = DiagnosticType.error("JSC_CANNOT_USE_PROTOTYPE_AND_VAR", "Rename prototypes and inline variables cannot be used together");
  final static DiagnosticType REPORT_PATH_IO_ERROR = DiagnosticType.error("JSC_REPORT_PATH_IO_ERROR", "Error writing compiler report to {0}");
  final private static DiagnosticType NAME_REF_GRAPH_FILE_ERROR = DiagnosticType.error("JSC_NAME_REF_GRAPH_FILE_ERROR", "Error \"{1}\" writing name reference graph to \"{0}\".");
  final private static DiagnosticType NAME_REF_REPORT_FILE_ERROR = DiagnosticType.error("JSC_NAME_REF_REPORT_FILE_ERROR", "Error \"{1}\" writing name reference report to \"{0}\".");
  final private static java.util.regex.Pattern GLOBAL_SYMBOL_NAMESPACE_PATTERN = java.util.regex.Pattern.compile("^[a-zA-Z0-9$_]+$");
  private GlobalNamespace namespaceForChecks = null;
  private PreprocessorSymbolTable preprocessorSymbolTable = null;
  private TightenTypes tightenTypes = null;
  private Set<String> exportedNames = null;
  private CrossModuleMethodMotion.IdGenerator crossModuleIdGenerator = new CrossModuleMethodMotion.IdGenerator();
  private Map<String, Integer> cssNames = null;
  private VariableMap variableMap = null;
  private VariableMap propertyMap = null;
  private VariableMap anonymousFunctionNameMap = null;
  private FunctionNames functionNames = null;
  private VariableMap stringMap = null;
  private String idGeneratorMap = null;
  final HotSwapPassFactory checkSideEffects = new HotSwapPassFactory("checkSideEffects", true) {
      @Override() protected HotSwapCompilerPass create(final AbstractCompiler compiler) {
        boolean protectHiddenSideEffects = options.protectHiddenSideEffects && !options.ideMode;
        return new CheckSideEffects(compiler, options.checkSuspiciousCode ? CheckLevel.WARNING : CheckLevel.OFF, protectHiddenSideEffects);
      }
  };
  final PassFactory stripSideEffectProtection = new PassFactory("stripSideEffectProtection", true) {
      @Override() protected CompilerPass create(final AbstractCompiler compiler) {
        return new CheckSideEffects.StripProtection(compiler);
      }
  };
  final HotSwapPassFactory suspiciousCode = new HotSwapPassFactory("suspiciousCode", true) {
      @Override() protected HotSwapCompilerPass create(final AbstractCompiler compiler) {
        List<Callback> sharedCallbacks = Lists.newArrayList();
        if(options.checkSuspiciousCode) {
          sharedCallbacks.add(new CheckSuspiciousCode());
        }
        if(options.enables(DiagnosticGroups.GLOBAL_THIS)) {
          sharedCallbacks.add(new CheckGlobalThis(compiler));
        }
        if(options.enables(DiagnosticGroups.DEBUGGER_STATEMENT_PRESENT)) {
          sharedCallbacks.add(new CheckDebuggerStatement(compiler));
        }
        return combineChecks(compiler, sharedCallbacks);
      }
  };
  final HotSwapPassFactory checkControlStructures = new HotSwapPassFactory("checkControlStructures", true) {
      @Override() protected HotSwapCompilerPass create(AbstractCompiler compiler) {
        return new ControlStructureCheck(compiler);
      }
  };
  final HotSwapPassFactory checkRequires = new HotSwapPassFactory("checkRequires", true) {
      @Override() protected HotSwapCompilerPass create(AbstractCompiler compiler) {
        return new CheckRequiresForConstructors(compiler, options.checkRequires);
      }
  };
  final HotSwapPassFactory checkProvides = new HotSwapPassFactory("checkProvides", true) {
      @Override() protected HotSwapCompilerPass create(AbstractCompiler compiler) {
        return new CheckProvides(compiler, options.checkProvides);
      }
  };
  final private static DiagnosticType GENERATE_EXPORTS_ERROR = DiagnosticType.error("JSC_GENERATE_EXPORTS_ERROR", "Exports can only be generated if export symbol/property " + "functions are set.");
  final PassFactory generateExports = new PassFactory("generateExports", true) {
      @Override() protected CompilerPass create(AbstractCompiler compiler) {
        CodingConvention convention = compiler.getCodingConvention();
        if(convention.getExportSymbolFunction() != null && convention.getExportPropertyFunction() != null) {
          return new GenerateExports(compiler, convention.getExportSymbolFunction(), convention.getExportPropertyFunction());
        }
        else {
          return new ErrorPass(compiler, GENERATE_EXPORTS_ERROR);
        }
      }
  };
  final PassFactory exportTestFunctions = new PassFactory("exportTestFunctions", true) {
      @Override() protected CompilerPass create(AbstractCompiler compiler) {
        CodingConvention convention = compiler.getCodingConvention();
        if(convention.getExportSymbolFunction() != null) {
          return new ExportTestFunctions(compiler, convention.getExportSymbolFunction(), convention.getExportPropertyFunction());
        }
        else {
          return new ErrorPass(compiler, GENERATE_EXPORTS_ERROR);
        }
      }
  };
  final PassFactory gatherRawExports = new PassFactory("gatherRawExports", true) {
      @Override() protected CompilerPass create(AbstractCompiler compiler) {
        final GatherRawExports pass = new GatherRawExports(compiler);
        return new CompilerPass() {
            @Override() public void process(Node externs, Node root) {
              pass.process(externs, root);
              if(exportedNames == null) {
                exportedNames = Sets.newHashSet();
              }
              exportedNames.addAll(pass.getExportedVariableNames());
            }
        };
      }
  };
  @SuppressWarnings(value = {"deprecation", }) final HotSwapPassFactory closurePrimitives = new HotSwapPassFactory("closurePrimitives", true) {
      @Override() protected HotSwapCompilerPass create(AbstractCompiler compiler) {
        maybeInitializePreprocessorSymbolTable(compiler);
        final ProcessClosurePrimitives pass = new ProcessClosurePrimitives(compiler, preprocessorSymbolTable, options.brokenClosureRequiresLevel);
        return new HotSwapCompilerPass() {
            @Override() public void process(Node externs, Node root) {
              pass.process(externs, root);
              exportedNames = pass.getExportedVariableNames();
            }
            @Override() public void hotSwapScript(Node scriptRoot, Node originalRoot) {
              pass.hotSwapScript(scriptRoot, originalRoot);
            }
        };
      }
  };
  final PassFactory jqueryAliases = new PassFactory("jqueryAliases", true) {
      @Override() protected CompilerPass create(AbstractCompiler compiler) {
        return new ExpandJqueryAliases(compiler);
      }
  };
  final PassFactory replaceMessages = new PassFactory("replaceMessages", true) {
      @Override() protected CompilerPass create(final AbstractCompiler compiler) {
        return new ReplaceMessages(compiler, options.messageBundle, true, JsMessage.Style.getFromParams(true, false), false);
      }
  };
  final PassFactory replaceMessagesForChrome = new PassFactory("replaceMessages", true) {
      @Override() protected CompilerPass create(final AbstractCompiler compiler) {
        return new ReplaceMessagesForChrome(compiler, new GoogleJsMessageIdGenerator(options.tcProjectId), true, JsMessage.Style.getFromParams(true, false));
      }
  };
  final HotSwapPassFactory closureGoogScopeAliases = new HotSwapPassFactory("closureGoogScopeAliases", true) {
      @Override() protected HotSwapCompilerPass create(AbstractCompiler compiler) {
        maybeInitializePreprocessorSymbolTable(compiler);
        return new ScopedAliases(compiler, preprocessorSymbolTable, options.getAliasTransformationHandler());
      }
  };
  final HotSwapPassFactory closureRewriteGoogClass = new HotSwapPassFactory("closureRewriteGoogClass", true) {
      @Override() protected HotSwapCompilerPass create(AbstractCompiler compiler) {
        return new ClosureRewriteClass(compiler);
      }
  };
  final PassFactory closureCheckGetCssName = new PassFactory("closureCheckGetCssName", true) {
      @Override() protected CompilerPass create(AbstractCompiler compiler) {
        String blacklist = options.checkMissingGetCssNameBlacklist;
        Preconditions.checkState(blacklist != null && !blacklist.isEmpty(), "Not checking use of goog.getCssName because of empty blacklist.");
        return new CheckMissingGetCssName(compiler, options.checkMissingGetCssNameLevel, blacklist);
      }
  };
  final PassFactory closureReplaceGetCssName = new PassFactory("closureReplaceGetCssName", true) {
      @Override() protected CompilerPass create(final AbstractCompiler compiler) {
        return new CompilerPass() {
            @Override() public void process(Node externs, Node jsRoot) {
              Map<String, Integer> newCssNames = null;
              if(options.gatherCssNames) {
                newCssNames = Maps.newHashMap();
              }
              ReplaceCssNames pass = new ReplaceCssNames(compiler, newCssNames, options.cssRenamingWhitelist);
              pass.process(externs, jsRoot);
              cssNames = newCssNames;
            }
        };
      }
  };
  final PassFactory createSyntheticBlocks = new PassFactory("createSyntheticBlocks", true) {
      @Override() protected CompilerPass create(AbstractCompiler compiler) {
        return new CreateSyntheticBlocks(compiler, options.syntheticBlockStartMarker, options.syntheticBlockEndMarker);
      }
  };
  final PassFactory peepholeOptimizations = new PassFactory("peepholeOptimizations", false) {
      @Override() protected CompilerPass create(AbstractCompiler compiler) {
        final boolean late = false;
        return new PeepholeOptimizationsPass(compiler, new PeepholeSubstituteAlternateSyntax(late), new PeepholeReplaceKnownMethods(late), new PeepholeRemoveDeadCode(), new PeepholeFoldConstants(late), new PeepholeCollectPropertyAssignments());
      }
  };
  final PassFactory latePeepholeOptimizations = new PassFactory("latePeepholeOptimizations", true) {
      @Override() protected CompilerPass create(AbstractCompiler compiler) {
        final boolean late = true;
        return new PeepholeOptimizationsPass(compiler, new StatementFusion(), new PeepholeRemoveDeadCode(), new PeepholeSubstituteAlternateSyntax(late), new PeepholeReplaceKnownMethods(late), new PeepholeFoldConstants(late), new ReorderConstantExpression());
      }
  };
  final HotSwapPassFactory checkVars = new HotSwapPassFactory("checkVars", true) {
      @Override() protected HotSwapCompilerPass create(AbstractCompiler compiler) {
        return new VarCheck(compiler);
      }
  };
  final PassFactory checkRegExp = new PassFactory("checkRegExp", true) {
      @Override() protected CompilerPass create(final AbstractCompiler compiler) {
        final CheckRegExp pass = new CheckRegExp(compiler);
        return new CompilerPass() {
            @Override() public void process(Node externs, Node root) {
              pass.process(externs, root);
              compiler.setHasRegExpGlobalReferences(pass.isGlobalRegExpPropertiesUsed());
            }
        };
      }
  };
  final HotSwapPassFactory checkVariableReferences = new HotSwapPassFactory("checkVariableReferences", true) {
      @Override() protected HotSwapCompilerPass create(AbstractCompiler compiler) {
        return new VariableReferenceCheck(compiler, options.aggressiveVarCheck);
      }
  };
  final PassFactory objectPropertyStringPreprocess = new PassFactory("ObjectPropertyStringPreprocess", true) {
      @Override() protected CompilerPass create(AbstractCompiler compiler) {
        return new ObjectPropertyStringPreprocess(compiler);
      }
  };
  final HotSwapPassFactory resolveTypes = new HotSwapPassFactory("resolveTypes", true) {
      @Override() protected HotSwapCompilerPass create(AbstractCompiler compiler) {
        return new GlobalTypeResolver(compiler);
      }
  };
  final PassFactory clearTypedScopePass = new PassFactory("clearTypedScopePass", true) {
      @Override() protected CompilerPass create(AbstractCompiler compiler) {
        return new ClearTypedScope();
      }
  };
  final HotSwapPassFactory inferTypes = new HotSwapPassFactory("inferTypes", true) {
      @Override() protected HotSwapCompilerPass create(final AbstractCompiler compiler) {
        return new HotSwapCompilerPass() {
            @Override() public void process(Node externs, Node root) {
              Preconditions.checkNotNull(topScope);
              Preconditions.checkNotNull(getTypedScopeCreator());
              makeTypeInference(compiler).process(externs, root);
            }
            @Override() public void hotSwapScript(Node scriptRoot, Node originalRoot) {
              makeTypeInference(compiler).inferAllScopes(scriptRoot);
            }
        };
      }
  };
  final HotSwapPassFactory inferJsDocInfo = new HotSwapPassFactory("inferJsDocInfo", true) {
      @Override() protected HotSwapCompilerPass create(final AbstractCompiler compiler) {
        return new HotSwapCompilerPass() {
            @Override() public void process(Node externs, Node root) {
              Preconditions.checkNotNull(topScope);
              Preconditions.checkNotNull(getTypedScopeCreator());
              makeInferJsDocInfo(compiler).process(externs, root);
            }
            @Override() public void hotSwapScript(Node scriptRoot, Node originalRoot) {
              makeInferJsDocInfo(compiler).hotSwapScript(scriptRoot, originalRoot);
            }
        };
      }
  };
  final HotSwapPassFactory checkTypes = new HotSwapPassFactory("checkTypes", true) {
      @Override() protected HotSwapCompilerPass create(final AbstractCompiler compiler) {
        return new HotSwapCompilerPass() {
            @Override() public void process(Node externs, Node root) {
              Preconditions.checkNotNull(topScope);
              Preconditions.checkNotNull(getTypedScopeCreator());
              TypeCheck var_1802 = makeTypeCheck(compiler);
              TypeCheck check = var_1802;
              check.process(externs, root);
              compiler.getErrorManager().setTypedPercent(check.getTypedPercent());
            }
            @Override() public void hotSwapScript(Node scriptRoot, Node originalRoot) {
              makeTypeCheck(compiler).check(scriptRoot, false);
            }
        };
      }
  };
  final HotSwapPassFactory checkControlFlow = new HotSwapPassFactory("checkControlFlow", true) {
      @Override() protected HotSwapCompilerPass create(AbstractCompiler compiler) {
        List<Callback> callbacks = Lists.newArrayList();
        if(options.checkUnreachableCode.isOn()) {
          callbacks.add(new CheckUnreachableCode(compiler, options.checkUnreachableCode));
        }
        if(options.checkMissingReturn.isOn() && options.checkTypes) {
          callbacks.add(new CheckMissingReturn(compiler, options.checkMissingReturn));
        }
        return combineChecks(compiler, callbacks);
      }
  };
  final HotSwapPassFactory checkAccessControls = new HotSwapPassFactory("checkAccessControls", true) {
      @Override() protected HotSwapCompilerPass create(AbstractCompiler compiler) {
        return new CheckAccessControls(compiler);
      }
  };
  final PassFactory checkGlobalNames = new PassFactory("checkGlobalNames", true) {
      @Override() protected CompilerPass create(final AbstractCompiler compiler) {
        return new CompilerPass() {
            @Override() public void process(Node externs, Node jsRoot) {
              namespaceForChecks = new GlobalNamespace(compiler, externs, jsRoot);
              new CheckGlobalNames(compiler, options.checkGlobalNamesLevel).injectNamespace(namespaceForChecks).process(externs, jsRoot);
            }
        };
      }
  };
  final PassFactory checkStrictMode = new PassFactory("checkStrictMode", true) {
      @Override() protected CompilerPass create(AbstractCompiler compiler) {
        return new StrictModeCheck(compiler, !options.checkSymbols, !options.checkCaja);
      }
  };
  final PassFactory processTweaks = new PassFactory("processTweaks", true) {
      @Override() protected CompilerPass create(final AbstractCompiler compiler) {
        return new CompilerPass() {
            @Override() public void process(Node externs, Node jsRoot) {
              new ProcessTweaks(compiler, options.getTweakProcessing().shouldStrip(), options.getTweakReplacements()).process(externs, jsRoot);
            }
        };
      }
  };
  final PassFactory processDefines = new PassFactory("processDefines", true) {
      @Override() protected CompilerPass create(final AbstractCompiler compiler) {
        return new CompilerPass() {
            @Override() public void process(Node externs, Node jsRoot) {
              Map<String, Node> replacements = getAdditionalReplacements(options);
              replacements.putAll(options.getDefineReplacements());
              new ProcessDefines(compiler, replacements).injectNamespace(namespaceForChecks).process(externs, jsRoot);
            }
        };
      }
  };
  final PassFactory garbageCollectChecks = new HotSwapPassFactory("garbageCollectChecks", true) {
      @Override() protected HotSwapCompilerPass create(final AbstractCompiler compiler) {
        return new HotSwapCompilerPass() {
            @Override() public void process(Node externs, Node jsRoot) {
              namespaceForChecks = null;
            }
            @Override() public void hotSwapScript(Node scriptRoot, Node originalRoot) {
              process(null, null);
            }
        };
      }
  };
  final PassFactory checkConsts = new PassFactory("checkConsts", true) {
      @Override() protected CompilerPass create(AbstractCompiler compiler) {
        return new ConstCheck(compiler);
      }
  };
  final PassFactory computeFunctionNames = new PassFactory("computeFunctionNames", true) {
      @Override() protected CompilerPass create(AbstractCompiler compiler) {
        return ((functionNames = new FunctionNames(compiler)));
      }
  };
  final PassFactory ignoreCajaProperties = new PassFactory("ignoreCajaProperties", true) {
      @Override() protected CompilerPass create(AbstractCompiler compiler) {
        return new IgnoreCajaProperties(compiler);
      }
  };
  final PassFactory runtimeTypeCheck = new PassFactory("runtimeTypeCheck", true) {
      @Override() protected CompilerPass create(AbstractCompiler compiler) {
        return new RuntimeTypeCheck(compiler, options.runtimeTypeCheckLogFunction);
      }
  };
  final PassFactory replaceIdGenerators = new PassFactory("replaceIdGenerators", true) {
      @Override() protected CompilerPass create(final AbstractCompiler compiler) {
        return new CompilerPass() {
            @Override() public void process(Node externs, Node root) {
              ReplaceIdGenerators pass = new ReplaceIdGenerators(compiler, options.idGenerators, options.generatePseudoNames, options.idGeneratorsMapSerialized);
              pass.process(externs, root);
              idGeneratorMap = pass.getSerializedIdMappings();
            }
        };
      }
  };
  final PassFactory replaceStrings = new PassFactory("replaceStrings", true) {
      @Override() protected CompilerPass create(final AbstractCompiler compiler) {
        return new CompilerPass() {
            @Override() public void process(Node externs, Node root) {
              ReplaceStrings pass = new ReplaceStrings(compiler, options.replaceStringsPlaceholderToken, options.replaceStringsFunctionDescriptions, options.replaceStringsReservedStrings, options.replaceStringsInputMap);
              pass.process(externs, root);
              stringMap = pass.getStringMap();
            }
        };
      }
  };
  final PassFactory optimizeArgumentsArray = new PassFactory("optimizeArgumentsArray", true) {
      @Override() protected CompilerPass create(AbstractCompiler compiler) {
        return new OptimizeArgumentsArray(compiler);
      }
  };
  final PassFactory closureCodeRemoval = new PassFactory("closureCodeRemoval", true) {
      @Override() protected CompilerPass create(final AbstractCompiler compiler) {
        return new ClosureCodeRemoval(compiler, options.removeAbstractMethods, options.removeClosureAsserts);
      }
  };
  final PassFactory closureOptimizePrimitives = new PassFactory("closureOptimizePrimitives", true) {
      @Override() protected CompilerPass create(final AbstractCompiler compiler) {
        return new ClosureOptimizePrimitives(compiler);
      }
  };
  final PassFactory rescopeGlobalSymbols = new PassFactory("rescopeGlobalSymbols", true) {
      @Override() protected CompilerPass create(AbstractCompiler compiler) {
        return new RescopeGlobalSymbols(compiler, options.renamePrefixNamespace);
      }
  };
  final PassFactory collapseProperties = new PassFactory("collapseProperties", true) {
      @Override() protected CompilerPass create(AbstractCompiler compiler) {
        return new CollapseProperties(compiler, options.collapsePropertiesOnExternTypes, !isInliningForbidden());
      }
  };
  final PassFactory collapseObjectLiterals = new PassFactory("collapseObjectLiterals", false) {
      @Override() protected CompilerPass create(AbstractCompiler compiler) {
        return new InlineObjectLiterals(compiler, compiler.getUniqueNameIdSupplier());
      }
  };
  final PassFactory tightenTypesBuilder = new PassFactory("tightenTypes", true) {
      @Override() protected CompilerPass create(AbstractCompiler compiler) {
        if(!options.checkTypes) {
          return new ErrorPass(compiler, TIGHTEN_TYPES_WITHOUT_TYPE_CHECK);
        }
        tightenTypes = new TightenTypes(compiler);
        return tightenTypes;
      }
  };
  final PassFactory disambiguateProperties = new PassFactory("disambiguateProperties", true) {
      @Override() protected CompilerPass create(AbstractCompiler compiler) {
        if(tightenTypes == null) {
          return DisambiguateProperties.forJSTypeSystem(compiler, options.propertyInvalidationErrors);
        }
        else {
          return DisambiguateProperties.forConcreteTypeSystem(compiler, tightenTypes, options.propertyInvalidationErrors);
        }
      }
  };
  final PassFactory chainCalls = new PassFactory("chainCalls", true) {
      @Override() protected CompilerPass create(AbstractCompiler compiler) {
        return new ChainCalls(compiler);
      }
  };
  final PassFactory devirtualizePrototypeMethods = new PassFactory("devirtualizePrototypeMethods", true) {
      @Override() protected CompilerPass create(AbstractCompiler compiler) {
        return new DevirtualizePrototypeMethods(compiler);
      }
  };
  final PassFactory optimizeCallsAndRemoveUnusedVars = new PassFactory("optimizeCalls_and_removeUnusedVars", false) {
      @Override() protected CompilerPass create(AbstractCompiler compiler) {
        OptimizeCalls passes = new OptimizeCalls(compiler);
        if(options.optimizeReturns) {
          passes.addPass(new OptimizeReturns(compiler));
        }
        if(options.optimizeParameters) {
          passes.addPass(new OptimizeParameters(compiler));
        }
        if(options.optimizeCalls) {
          boolean removeOnlyLocals = options.removeUnusedLocalVars && !options.removeUnusedVars;
          boolean preserveAnonymousFunctionNames = options.anonymousFunctionNaming != AnonymousFunctionNamingPolicy.OFF;
          passes.addPass(new RemoveUnusedVars(compiler, !removeOnlyLocals, preserveAnonymousFunctionNames, true));
        }
        return passes;
      }
  };
  final PassFactory markPureFunctions = new PassFactory("markPureFunctions", true) {
      @Override() protected CompilerPass create(AbstractCompiler compiler) {
        return new PureFunctionIdentifier.Driver(compiler, options.debugFunctionSideEffectsPath, false);
      }
  };
  final PassFactory markNoSideEffectCalls = new PassFactory("markNoSideEffectCalls", true) {
      @Override() protected CompilerPass create(AbstractCompiler compiler) {
        return new MarkNoSideEffectCalls(compiler);
      }
  };
  final PassFactory inlineVariables = new PassFactory("inlineVariables", false) {
      @Override() protected CompilerPass create(AbstractCompiler compiler) {
        if(isInliningForbidden()) {
          return new ErrorPass(compiler, CANNOT_USE_PROTOTYPE_AND_VAR);
        }
        else {
          InlineVariables.Mode mode;
          if(options.inlineVariables) {
            mode = InlineVariables.Mode.ALL;
          }
          else 
            if(options.inlineLocalVariables) {
              mode = InlineVariables.Mode.LOCALS_ONLY;
            }
            else {
              throw new IllegalStateException("No variable inlining option set.");
            }
          return new InlineVariables(compiler, mode, true);
        }
      }
  };
  final PassFactory inlineConstants = new PassFactory("inlineConstants", false) {
      @Override() protected CompilerPass create(AbstractCompiler compiler) {
        return new InlineVariables(compiler, InlineVariables.Mode.CONSTANTS_ONLY, true);
      }
  };
  final PassFactory minimizeExitPoints = new PassFactory("minimizeExitPoints", false) {
      @Override() protected CompilerPass create(AbstractCompiler compiler) {
        return new MinimizeExitPoints(compiler);
      }
  };
  final PassFactory removeUnreachableCode = new PassFactory("removeUnreachableCode", false) {
      @Override() protected CompilerPass create(AbstractCompiler compiler) {
        return new UnreachableCodeElimination(compiler, true);
      }
  };
  final PassFactory removeUnusedPrototypeProperties = new PassFactory("removeUnusedPrototypeProperties", false) {
      @Override() protected CompilerPass create(AbstractCompiler compiler) {
        return new RemoveUnusedPrototypeProperties(compiler, options.removeUnusedPrototypePropertiesInExterns, !options.removeUnusedVars);
      }
  };
  final PassFactory removeUnusedClassProperties = new PassFactory("removeUnusedClassProperties", false) {
      @Override() protected CompilerPass create(AbstractCompiler compiler) {
        return new RemoveUnusedClassProperties(compiler);
      }
  };
  final PassFactory smartNamePass = new PassFactory("smartNamePass", true) {
      @Override() protected CompilerPass create(final AbstractCompiler compiler) {
        return new CompilerPass() {
            @Override() public void process(Node externs, Node root) {
              NameAnalyzer na = new NameAnalyzer(compiler, false);
              na.process(externs, root);
              String reportPath = options.reportPath;
              if(reportPath != null) {
                try {
                  Files.write(na.getHtmlReport(), new File(reportPath), Charsets.UTF_8);
                }
                catch (IOException e) {
                  compiler.report(JSError.make(REPORT_PATH_IO_ERROR, reportPath));
                }
              }
              if(options.smartNameRemoval) {
                na.removeUnreferenced();
              }
            }
        };
      }
  };
  final PassFactory smartNamePass2 = new PassFactory("smartNamePass", true) {
      @Override() protected CompilerPass create(final AbstractCompiler compiler) {
        return new CompilerPass() {
            @Override() public void process(Node externs, Node root) {
              NameAnalyzer na = new NameAnalyzer(compiler, false);
              na.process(externs, root);
              na.removeUnreferenced();
            }
        };
      }
  };
  final PassFactory inlineSimpleMethods = new PassFactory("inlineSimpleMethods", false) {
      @Override() protected CompilerPass create(AbstractCompiler compiler) {
        return new InlineSimpleMethods(compiler);
      }
  };
  final PassFactory deadAssignmentsElimination = new PassFactory("deadAssignmentsElimination", false) {
      @Override() protected CompilerPass create(AbstractCompiler compiler) {
        return new DeadAssignmentsElimination(compiler);
      }
  };
  final PassFactory inlineFunctions = new PassFactory("inlineFunctions", false) {
      @Override() protected CompilerPass create(AbstractCompiler compiler) {
        boolean enableBlockInlining = !isInliningForbidden();
        return new InlineFunctions(compiler, compiler.getUniqueNameIdSupplier(), options.inlineFunctions, options.inlineLocalFunctions, enableBlockInlining, options.assumeStrictThis() || options.getLanguageIn() == LanguageMode.ECMASCRIPT5_STRICT, true);
      }
  };
  final PassFactory inlineProperties = new PassFactory("inlineProperties", false) {
      @Override() protected CompilerPass create(AbstractCompiler compiler) {
        return new InlineProperties(compiler);
      }
  };
  final PassFactory removeUnusedVars = new PassFactory("removeUnusedVars", false) {
      @Override() protected CompilerPass create(AbstractCompiler compiler) {
        boolean removeOnlyLocals = options.removeUnusedLocalVars && !options.removeUnusedVars;
        boolean preserveAnonymousFunctionNames = options.anonymousFunctionNaming != AnonymousFunctionNamingPolicy.OFF;
        return new RemoveUnusedVars(compiler, !removeOnlyLocals, preserveAnonymousFunctionNames, false);
      }
  };
  final PassFactory crossModuleCodeMotion = new PassFactory("crossModuleCodeMotion", false) {
      @Override() protected CompilerPass create(AbstractCompiler compiler) {
        return new CrossModuleCodeMotion(compiler, compiler.getModuleGraph());
      }
  };
  final PassFactory crossModuleMethodMotion = new PassFactory("crossModuleMethodMotion", false) {
      @Override() protected CompilerPass create(AbstractCompiler compiler) {
        return new CrossModuleMethodMotion(compiler, crossModuleIdGenerator, options.removeUnusedPrototypePropertiesInExterns);
      }
  };
  final PassFactory specializeInitialModule = new PassFactory("specializeInitialModule", true) {
      @Override() protected CompilerPass create(AbstractCompiler compiler) {
        return new SpecializeModule(compiler, devirtualizePrototypeMethods, inlineFunctions, removeUnusedPrototypeProperties);
      }
  };
  final PassFactory flowSensitiveInlineVariables = new PassFactory("flowSensitiveInlineVariables", true) {
      @Override() protected CompilerPass create(AbstractCompiler compiler) {
        return new FlowSensitiveInlineVariables(compiler);
      }
  };
  final PassFactory coalesceVariableNames = new PassFactory("coalesceVariableNames", true) {
      @Override() protected CompilerPass create(AbstractCompiler compiler) {
        return new CoalesceVariableNames(compiler, options.generatePseudoNames);
      }
  };
  final PassFactory exploitAssign = new PassFactory("exploitAssign", true) {
      @Override() protected CompilerPass create(AbstractCompiler compiler) {
        return new PeepholeOptimizationsPass(compiler, new ExploitAssigns());
      }
  };
  final PassFactory collapseVariableDeclarations = new PassFactory("collapseVariableDeclarations", true) {
      @Override() protected CompilerPass create(AbstractCompiler compiler) {
        return new CollapseVariableDeclarations(compiler);
      }
  };
  final PassFactory groupVariableDeclarations = new PassFactory("groupVariableDeclarations", true) {
      @Override() protected CompilerPass create(AbstractCompiler compiler) {
        return new GroupVariableDeclarations(compiler);
      }
  };
  final PassFactory extractPrototypeMemberDeclarations = new PassFactory("extractPrototypeMemberDeclarations", true) {
      @Override() protected CompilerPass create(AbstractCompiler compiler) {
        return new ExtractPrototypeMemberDeclarations(compiler, Pattern.USE_GLOBAL_TEMP);
      }
  };
  final PassFactory rewriteFunctionExpressions = new PassFactory("rewriteFunctionExpressions", true) {
      @Override() protected CompilerPass create(AbstractCompiler compiler) {
        return new FunctionRewriter(compiler);
      }
  };
  final PassFactory collapseAnonymousFunctions = new PassFactory("collapseAnonymousFunctions", true) {
      @Override() protected CompilerPass create(AbstractCompiler compiler) {
        return new CollapseAnonymousFunctions(compiler);
      }
  };
  final PassFactory moveFunctionDeclarations = new PassFactory("moveFunctionDeclarations", true) {
      @Override() protected CompilerPass create(AbstractCompiler compiler) {
        return new MoveFunctionDeclarations(compiler);
      }
  };
  final PassFactory nameUnmappedAnonymousFunctions = new PassFactory("nameAnonymousFunctions", true) {
      @Override() protected CompilerPass create(AbstractCompiler compiler) {
        return new NameAnonymousFunctions(compiler);
      }
  };
  final PassFactory nameMappedAnonymousFunctions = new PassFactory("nameAnonymousFunctions", true) {
      @Override() protected CompilerPass create(final AbstractCompiler compiler) {
        return new CompilerPass() {
            @Override() public void process(Node externs, Node root) {
              NameAnonymousFunctionsMapped naf = new NameAnonymousFunctionsMapped(compiler, options.inputAnonymousFunctionNamingMap);
              naf.process(externs, root);
              anonymousFunctionNameMap = naf.getFunctionMap();
            }
        };
      }
  };
  final PassFactory aliasExternals = new PassFactory("aliasExternals", true) {
      @Override() protected CompilerPass create(AbstractCompiler compiler) {
        return new AliasExternals(compiler, compiler.getModuleGraph(), options.unaliasableGlobals, options.aliasableGlobals);
      }
  };
  final PassFactory aliasStrings = new PassFactory("aliasStrings", true) {
      @Override() protected CompilerPass create(AbstractCompiler compiler) {
        return new AliasStrings(compiler, compiler.getModuleGraph(), options.aliasAllStrings ? null : options.aliasableStrings, options.aliasStringsBlacklist, options.outputJsStringUsage);
      }
  };
  final PassFactory aliasKeywords = new PassFactory("aliasKeywords", true) {
      @Override() protected CompilerPass create(AbstractCompiler compiler) {
        return new AliasKeywords(compiler);
      }
  };
  final PassFactory objectPropertyStringPostprocess = new PassFactory("ObjectPropertyStringPostprocess", true) {
      @Override() protected CompilerPass create(AbstractCompiler compiler) {
        return new ObjectPropertyStringPostprocess(compiler);
      }
  };
  final PassFactory ambiguateProperties = new PassFactory("ambiguateProperties", true) {
      @Override() protected CompilerPass create(AbstractCompiler compiler) {
        return new AmbiguateProperties(compiler, options.anonymousFunctionNaming.getReservedCharacters());
      }
  };
  final PassFactory markUnnormalized = new PassFactory("markUnnormalized", true) {
      @Override() protected CompilerPass create(final AbstractCompiler compiler) {
        return new CompilerPass() {
            @Override() public void process(Node externs, Node root) {
              compiler.setLifeCycleStage(LifeCycleStage.RAW);
            }
        };
      }
  };
  final PassFactory denormalize = new PassFactory("denormalize", true) {
      @Override() protected CompilerPass create(AbstractCompiler compiler) {
        return new Denormalize(compiler);
      }
  };
  final PassFactory invertContextualRenaming = new PassFactory("invertContextualRenaming", true) {
      @Override() protected CompilerPass create(AbstractCompiler compiler) {
        return MakeDeclaredNamesUnique.getContextualRenameInverter(compiler);
      }
  };
  final PassFactory renameProperties = new PassFactory("renameProperties", true) {
      @Override() protected CompilerPass create(final AbstractCompiler compiler) {
        final VariableMap prevPropertyMap = options.inputPropertyMap;
        return new CompilerPass() {
            @Override() public void process(Node externs, Node root) {
              propertyMap = runPropertyRenaming(compiler, prevPropertyMap, externs, root);
            }
        };
      }
  };
  final PassFactory renameVars = new PassFactory("renameVars", true) {
      @Override() protected CompilerPass create(final AbstractCompiler compiler) {
        final VariableMap prevVariableMap = options.inputVariableMap;
        return new CompilerPass() {
            @Override() public void process(Node externs, Node root) {
              variableMap = runVariableRenaming(compiler, prevVariableMap, externs, root);
            }
        };
      }
  };
  final PassFactory renameLabels = new PassFactory("renameLabels", true) {
      @Override() protected CompilerPass create(AbstractCompiler compiler) {
        return new RenameLabels(compiler);
      }
  };
  final PassFactory convertToDottedProperties = new PassFactory("convertToDottedProperties", true) {
      @Override() protected CompilerPass create(AbstractCompiler compiler) {
        return new ConvertToDottedProperties(compiler);
      }
  };
  final PassFactory sanityCheckAst = new PassFactory("sanityCheckAst", true) {
      @Override() protected CompilerPass create(AbstractCompiler compiler) {
        return new AstValidator();
      }
  };
  final PassFactory sanityCheckVars = new PassFactory("sanityCheckVars", true) {
      @Override() protected CompilerPass create(AbstractCompiler compiler) {
        return new VarCheck(compiler, true);
      }
  };
  final PassFactory instrumentFunctions = new PassFactory("instrumentFunctions", true) {
      @Override() protected CompilerPass create(final AbstractCompiler compiler) {
        return new CompilerPass() {
            @Override() public void process(Node externs, Node root) {
              try {
                FileReader templateFile = new FileReader(options.instrumentationTemplate);
                (new InstrumentFunctions(compiler, functionNames, options.instrumentationTemplate, options.appNameStr, templateFile)).process(externs, root);
              }
              catch (IOException e) {
                compiler.report(JSError.make(AbstractCompiler.READ_ERROR, options.instrumentationTemplate));
              }
            }
        };
      }
  };
  final PassFactory printNameReferenceGraph = new PassFactory("printNameReferenceGraph", true) {
      @Override() protected CompilerPass create(final AbstractCompiler compiler) {
        return new CompilerPass() {
            @Override() public void process(Node externs, Node jsRoot) {
              NameReferenceGraphConstruction gc = new NameReferenceGraphConstruction(compiler);
              gc.process(externs, jsRoot);
              String graphFileName = options.nameReferenceGraphPath;
              try {
                Files.write(DotFormatter.toDot(gc.getNameReferenceGraph()), new File(graphFileName), Charsets.UTF_8);
              }
              catch (IOException e) {
                compiler.report(JSError.make(NAME_REF_GRAPH_FILE_ERROR, e.getMessage(), graphFileName));
              }
            }
        };
      }
  };
  final PassFactory printNameReferenceReport = new PassFactory("printNameReferenceReport", true) {
      @Override() protected CompilerPass create(final AbstractCompiler compiler) {
        return new CompilerPass() {
            @Override() public void process(Node externs, Node jsRoot) {
              NameReferenceGraphConstruction gc = new NameReferenceGraphConstruction(compiler);
              String reportFileName = options.nameReferenceReportPath;
              try {
                NameReferenceGraphReport report = new NameReferenceGraphReport(gc.getNameReferenceGraph());
                Files.write(report.getHtmlReport(), new File(reportFileName), Charsets.UTF_8);
              }
              catch (IOException e) {
                compiler.report(JSError.make(NAME_REF_REPORT_FILE_ERROR, e.getMessage(), reportFileName));
              }
            }
        };
      }
  };
  public DefaultPassConfig(CompilerOptions options) {
    super(options);
  }
  private static CompilerPass runInSerial(final CompilerPass ... passes) {
    return runInSerial(Lists.newArrayList(passes));
  }
  private static CompilerPass runInSerial(final Collection<CompilerPass> passes) {
    return new CompilerPass() {
        @Override() public void process(Node externs, Node root) {
          for (CompilerPass pass : passes) {
            pass.process(externs, root);
          }
        }
    };
  }
  GlobalNamespace getGlobalNamespace() {
    return namespaceForChecks;
  }
  private static HotSwapCompilerPass combineChecks(AbstractCompiler compiler, List<Callback> callbacks) {
    Preconditions.checkArgument(callbacks.size() > 0);
    Callback[] array = callbacks.toArray(new Callback[callbacks.size()]);
    return new CombinedCompilerPass(compiler, array);
  }
  @Override() protected List<PassFactory> getChecks() {
    List<PassFactory> checks = Lists.newArrayList();
    checks.add(createEmptyPass("beforeStandardChecks"));
    if(options.closurePass) {
      checks.add(closureGoogScopeAliases);
      checks.add(closureRewriteGoogClass);
    }
    if(options.nameAnonymousFunctionsOnly) {
      if(options.anonymousFunctionNaming == AnonymousFunctionNamingPolicy.MAPPED) {
        checks.add(nameMappedAnonymousFunctions);
      }
      else 
        if(options.anonymousFunctionNaming == AnonymousFunctionNamingPolicy.UNMAPPED) {
          checks.add(nameUnmappedAnonymousFunctions);
        }
      return checks;
    }
    if(options.jqueryPass) {
      checks.add(jqueryAliases);
    }
    checks.add(checkSideEffects);
    if(options.checkSuspiciousCode || options.enables(DiagnosticGroups.GLOBAL_THIS) || options.enables(DiagnosticGroups.DEBUGGER_STATEMENT_PRESENT)) {
      checks.add(suspiciousCode);
    }
    if(options.checkControlStructures || options.enables(DiagnosticGroups.ES5_STRICT)) {
      checks.add(checkControlStructures);
    }
    if(options.checkRequires.isOn()) {
      checks.add(checkRequires);
    }
    if(options.checkProvides.isOn()) {
      checks.add(checkProvides);
    }
    if(options.generateExports) {
      checks.add(generateExports);
    }
    if(options.exportTestFunctions) {
      checks.add(exportTestFunctions);
    }
    if(options.closurePass) {
      checks.add(closurePrimitives);
    }
    if(options.closurePass && options.checkMissingGetCssNameLevel.isOn()) {
      checks.add(closureCheckGetCssName);
    }
    if(options.syntheticBlockStartMarker != null) {
      checks.add(createSyntheticBlocks);
    }
    checks.add(checkVars);
    if(options.computeFunctionSideEffects) {
      checks.add(checkRegExp);
    }
    if(options.aggressiveVarCheck.isOn()) {
      checks.add(checkVariableReferences);
    }
    if(options.processObjectPropertyString) {
      checks.add(objectPropertyStringPreprocess);
    }
    if(options.checkTypes || options.inferTypes) {
      checks.add(resolveTypes);
      checks.add(inferTypes);
      if(options.checkTypes) {
        checks.add(checkTypes);
      }
      else {
        checks.add(inferJsDocInfo);
      }
      if(!options.ideMode && !options.saveDataStructures) {
        checks.add(clearTypedScopePass);
      }
    }
    if(options.checkUnreachableCode.isOn() || (options.checkTypes && options.checkMissingReturn.isOn())) {
      checks.add(checkControlFlow);
    }
    if(options.checkTypes && (options.enables(DiagnosticGroups.ACCESS_CONTROLS) || options.enables(DiagnosticGroups.CONSTANT_PROPERTY))) {
      checks.add(checkAccessControls);
    }
    if(options.checkGlobalNamesLevel.isOn()) {
      checks.add(checkGlobalNames);
    }
    if(options.enables(DiagnosticGroups.ES5_STRICT) || options.checkCaja) {
      checks.add(checkStrictMode);
    }
    if(options.closurePass) {
      checks.add(closureReplaceGetCssName);
    }
    if(options.replaceMessagesWithChromeI18n) {
      checks.add(replaceMessagesForChrome);
    }
    else 
      if(options.messageBundle != null) {
        checks.add(replaceMessages);
      }
    if(options.getTweakProcessing().isOn()) {
      checks.add(processTweaks);
    }
    checks.add(processDefines);
    if(options.instrumentationTemplate != null || options.recordFunctionInformation) {
      checks.add(computeFunctionNames);
    }
    if(options.nameReferenceGraphPath != null && !options.nameReferenceGraphPath.isEmpty()) {
      checks.add(printNameReferenceGraph);
    }
    if(options.nameReferenceReportPath != null && !options.nameReferenceReportPath.isEmpty()) {
      checks.add(printNameReferenceReport);
    }
    checks.add(createEmptyPass("afterStandardChecks"));
    assertAllOneTimePasses(checks);
    return checks;
  }
  private List<PassFactory> getCodeRemovingPasses() {
    List<PassFactory> passes = Lists.newArrayList();
    if(options.collapseObjectLiterals && !isInliningForbidden()) {
      passes.add(collapseObjectLiterals);
    }
    if(options.inlineVariables || options.inlineLocalVariables) {
      passes.add(inlineVariables);
    }
    else 
      if(options.inlineConstantVars) {
        passes.add(inlineConstants);
      }
    if(options.foldConstants) {
      passes.add(minimizeExitPoints);
      passes.add(peepholeOptimizations);
    }
    if(options.removeDeadCode) {
      passes.add(removeUnreachableCode);
    }
    if(options.removeUnusedPrototypeProperties) {
      passes.add(removeUnusedPrototypeProperties);
    }
    if(options.removeUnusedClassProperties && !isInliningForbidden()) {
      passes.add(removeUnusedClassProperties);
    }
    assertAllLoopablePasses(passes);
    return passes;
  }
  private List<PassFactory> getMainOptimizationLoop() {
    List<PassFactory> passes = Lists.newArrayList();
    if(options.inlineGetters) {
      passes.add(inlineSimpleMethods);
    }
    passes.addAll(getCodeRemovingPasses());
    if(options.inlineFunctions || options.inlineLocalFunctions) {
      passes.add(inlineFunctions);
    }
    if(options.inlineProperties) {
      passes.add(inlineProperties);
    }
    boolean runOptimizeCalls = options.optimizeCalls || options.optimizeParameters || options.optimizeReturns;
    if(options.removeUnusedVars || options.removeUnusedLocalVars) {
      if(options.deadAssignmentElimination) {
        passes.add(deadAssignmentsElimination);
      }
      if(!runOptimizeCalls) {
        passes.add(removeUnusedVars);
      }
    }
    if(runOptimizeCalls) {
      passes.add(optimizeCallsAndRemoveUnusedVars);
    }
    assertAllLoopablePasses(passes);
    return passes;
  }
  @Override() protected List<PassFactory> getOptimizations() {
    List<PassFactory> passes = Lists.newArrayList();
    passes.add(garbageCollectChecks);
    if(options.runtimeTypeCheck) {
      passes.add(runtimeTypeCheck);
    }
    passes.add(createEmptyPass("beforeStandardOptimizations"));
    if(options.replaceIdGenerators) {
      passes.add(replaceIdGenerators);
    }
    if(options.optimizeArgumentsArray) {
      passes.add(optimizeArgumentsArray);
    }
    if(options.closurePass && (options.removeAbstractMethods || options.removeClosureAsserts)) {
      passes.add(closureCodeRemoval);
    }
    if(options.collapseProperties) {
      passes.add(collapseProperties);
    }
    if(!options.replaceStringsFunctionDescriptions.isEmpty()) {
      passes.add(replaceStrings);
    }
    if(options.tightenTypes) {
      passes.add(tightenTypesBuilder);
    }
    if(options.disambiguateProperties) {
      passes.add(disambiguateProperties);
    }
    if(options.computeFunctionSideEffects) {
      passes.add(markPureFunctions);
    }
    else 
      if(options.markNoSideEffectCalls) {
        passes.add(markNoSideEffectCalls);
      }
    if(options.chainCalls) {
      passes.add(chainCalls);
    }
    passes.add(checkConsts);
    if(options.ignoreCajaProperties) {
      passes.add(ignoreCajaProperties);
    }
    assertAllOneTimePasses(passes);
    if(options.smartNameRemoval || options.reportPath != null) {
      passes.addAll(getCodeRemovingPasses());
      passes.add(smartNamePass);
    }
    if(options.closurePass) {
      passes.add(closureOptimizePrimitives);
    }
    if(options.crossModuleCodeMotion) {
      passes.add(crossModuleCodeMotion);
    }
    if(options.devirtualizePrototypeMethods) {
      passes.add(devirtualizePrototypeMethods);
    }
    if(options.customPasses != null) {
      passes.add(getCustomPasses(CustomPassExecutionTime.BEFORE_OPTIMIZATION_LOOP));
    }
    passes.add(createEmptyPass("beforeMainOptimizations"));
    passes.addAll(getMainOptimizationLoop());
    if(options.specializeInitialModule) {
      if(options.crossModuleCodeMotion) {
        passes.add(crossModuleCodeMotion);
      }
      if(options.crossModuleMethodMotion) {
        passes.add(crossModuleMethodMotion);
      }
      passes.add(specializeInitialModule);
      passes.addAll(getMainOptimizationLoop());
    }
    passes.add(createEmptyPass("beforeModuleMotion"));
    if(options.crossModuleCodeMotion) {
      passes.add(crossModuleCodeMotion);
    }
    if(options.crossModuleMethodMotion) {
      passes.add(crossModuleMethodMotion);
    }
    passes.add(createEmptyPass("afterModuleMotion"));
    if(options.customPasses != null) {
      passes.add(getCustomPasses(CustomPassExecutionTime.AFTER_OPTIMIZATION_LOOP));
    }
    if(options.flowSensitiveInlineVariables) {
      passes.add(flowSensitiveInlineVariables);
      if(options.removeUnusedVars || options.removeUnusedLocalVars) {
        passes.add(removeUnusedVars);
      }
    }
    if(options.smartNameRemoval) {
      passes.add(smartNamePass2);
    }
    if(options.collapseAnonymousFunctions) {
      passes.add(collapseAnonymousFunctions);
    }
    if(options.moveFunctionDeclarations || options.renamePrefixNamespace != null) {
      passes.add(moveFunctionDeclarations);
    }
    if(options.anonymousFunctionNaming == AnonymousFunctionNamingPolicy.MAPPED) {
      passes.add(nameMappedAnonymousFunctions);
    }
    if(options.extractPrototypeMemberDeclarations && (options.propertyRenaming != PropertyRenamingPolicy.HEURISTIC && options.propertyRenaming != PropertyRenamingPolicy.AGGRESSIVE_HEURISTIC)) {
      passes.add(extractPrototypeMemberDeclarations);
    }
    if(options.ambiguateProperties && (options.propertyRenaming == PropertyRenamingPolicy.ALL_UNQUOTED)) {
      passes.add(ambiguateProperties);
    }
    if(options.propertyRenaming != PropertyRenamingPolicy.OFF) {
      passes.add(renameProperties);
    }
    if(options.reserveRawExports) {
      passes.add(gatherRawExports);
    }
    if(options.convertToDottedProperties) {
      passes.add(convertToDottedProperties);
    }
    if(options.rewriteFunctionExpressions) {
      passes.add(rewriteFunctionExpressions);
    }
    if(!options.aliasableStrings.isEmpty() || options.aliasAllStrings) {
      passes.add(aliasStrings);
    }
    if(options.aliasExternals) {
      passes.add(aliasExternals);
    }
    if(options.aliasKeywords) {
      passes.add(aliasKeywords);
    }
    passes.add(markUnnormalized);
    if(options.coalesceVariableNames) {
      passes.add(coalesceVariableNames);
      if(options.foldConstants) {
        passes.add(peepholeOptimizations);
      }
    }
    if(options.collapseVariableDeclarations) {
      passes.add(exploitAssign);
      passes.add(collapseVariableDeclarations);
    }
    passes.add(denormalize);
    if(options.instrumentationTemplate != null) {
      passes.add(instrumentFunctions);
    }
    if(options.variableRenaming != VariableRenamingPolicy.ALL) {
      passes.add(invertContextualRenaming);
    }
    if(options.variableRenaming != VariableRenamingPolicy.OFF) {
      passes.add(renameVars);
    }
    if(options.groupVariableDeclarations) {
      passes.add(groupVariableDeclarations);
    }
    if(options.processObjectPropertyString) {
      passes.add(objectPropertyStringPostprocess);
    }
    if(options.labelRenaming) {
      passes.add(renameLabels);
    }
    if(options.foldConstants) {
      passes.add(latePeepholeOptimizations);
    }
    if(options.anonymousFunctionNaming == AnonymousFunctionNamingPolicy.UNMAPPED) {
      passes.add(nameUnmappedAnonymousFunctions);
    }
    passes.add(stripSideEffectProtection);
    if(options.renamePrefixNamespace != null) {
      if(!GLOBAL_SYMBOL_NAMESPACE_PATTERN.matcher(options.renamePrefixNamespace).matches()) {
        throw new IllegalArgumentException("Illegal character in renamePrefixNamespace name: " + options.renamePrefixNamespace);
      }
      passes.add(rescopeGlobalSymbols);
    }
    passes.add(sanityCheckAst);
    passes.add(sanityCheckVars);
    return passes;
  }
  @VisibleForTesting() static Map<String, Node> getAdditionalReplacements(CompilerOptions options) {
    Map<String, Node> additionalReplacements = Maps.newHashMap();
    if(options.markAsCompiled || options.closurePass) {
      additionalReplacements.put(COMPILED_CONSTANT_NAME, IR.trueNode());
    }
    if(options.closurePass && options.locale != null) {
      additionalReplacements.put(CLOSURE_LOCALE_CONSTANT_NAME, IR.string(options.locale));
    }
    return additionalReplacements;
  }
  static PassFactory createEmptyPass(String name) {
    return new PassFactory(name, true) {
        @Override() protected CompilerPass create(final AbstractCompiler compiler) {
          return runInSerial();
        }
    };
  }
  private PassFactory getCustomPasses(final CustomPassExecutionTime executionTime) {
    return new PassFactory("runCustomPasses", true) {
        @Override() protected CompilerPass create(final AbstractCompiler compiler) {
          return runInSerial(options.customPasses.get(executionTime));
        }
    };
  }
  PreprocessorSymbolTable getPreprocessorSymbolTable() {
    return preprocessorSymbolTable;
  }
  @Override() protected State getIntermediateState() {
    return new State(cssNames == null ? null : Maps.newHashMap(cssNames), exportedNames == null ? null : Collections.unmodifiableSet(exportedNames), crossModuleIdGenerator, variableMap, propertyMap, anonymousFunctionNameMap, stringMap, functionNames, idGeneratorMap);
  }
  private VariableMap runPropertyRenaming(AbstractCompiler compiler, VariableMap prevPropertyMap, Node externs, Node root) {
    char[] reservedChars = options.anonymousFunctionNaming.getReservedCharacters();
    switch (options.propertyRenaming){
      case HEURISTIC:
      RenamePrototypes rproto = new RenamePrototypes(compiler, false, reservedChars, prevPropertyMap);
      rproto.process(externs, root);
      return rproto.getPropertyMap();
      case AGGRESSIVE_HEURISTIC:
      RenamePrototypes rproto2 = new RenamePrototypes(compiler, true, reservedChars, prevPropertyMap);
      rproto2.process(externs, root);
      return rproto2.getPropertyMap();
      case ALL_UNQUOTED:
      RenameProperties rprop = new RenameProperties(compiler, options.propertyAffinity, options.generatePseudoNames, prevPropertyMap, reservedChars);
      rprop.process(externs, root);
      return rprop.getPropertyMap();
      default:
      throw new IllegalStateException("Unrecognized property renaming policy");
    }
  }
  private VariableMap runVariableRenaming(AbstractCompiler compiler, VariableMap prevVariableMap, Node externs, Node root) {
    char[] reservedChars = options.anonymousFunctionNaming.getReservedCharacters();
    boolean preserveAnonymousFunctionNames = options.anonymousFunctionNaming != AnonymousFunctionNamingPolicy.OFF;
    Set<String> reservedNames = Sets.newHashSet();
    if(exportedNames != null) {
      reservedNames.addAll(exportedNames);
    }
    reservedNames.addAll(ParserRunner.getReservedVars());
    RenameVars rn = new RenameVars(compiler, options.renamePrefix, options.variableRenaming == VariableRenamingPolicy.LOCAL, preserveAnonymousFunctionNames, options.generatePseudoNames, options.shadowVariables, prevVariableMap, reservedChars, reservedNames);
    rn.process(externs, root);
    return rn.getVariableMap();
  }
  private boolean isInliningForbidden() {
    return options.propertyRenaming == PropertyRenamingPolicy.HEURISTIC || options.propertyRenaming == PropertyRenamingPolicy.AGGRESSIVE_HEURISTIC;
  }
  private void assertAllLoopablePasses(List<PassFactory> passes) {
    for (PassFactory pass : passes) {
      Preconditions.checkState(!pass.isOneTimePass());
    }
  }
  private void assertAllOneTimePasses(List<PassFactory> passes) {
    for (PassFactory pass : passes) {
      Preconditions.checkState(pass.isOneTimePass());
    }
  }
  void maybeInitializePreprocessorSymbolTable(AbstractCompiler compiler) {
    if(options.ideMode) {
      Node root = compiler.getRoot();
      if(preprocessorSymbolTable == null || preprocessorSymbolTable.getRootNode() != root) {
        preprocessorSymbolTable = new PreprocessorSymbolTable(root);
      }
    }
  }
  @Override() protected void setIntermediateState(State state) {
    this.cssNames = state.cssNames == null ? null : Maps.newHashMap(state.cssNames);
    this.exportedNames = state.exportedNames == null ? null : Sets.newHashSet(state.exportedNames);
    this.crossModuleIdGenerator = state.crossModuleIdGenerator;
    this.variableMap = state.variableMap;
    this.propertyMap = state.propertyMap;
    this.anonymousFunctionNameMap = state.anonymousFunctionNameMap;
    this.stringMap = state.stringMap;
    this.functionNames = state.functionNames;
    this.idGeneratorMap = state.idGeneratorMap;
  }
  
  class ClearTypedScope implements CompilerPass  {
    @Override() public void process(Node externs, Node root) {
      clearTypedScope();
    }
  }
  
  class GlobalTypeResolver implements HotSwapCompilerPass  {
    final private AbstractCompiler compiler;
    GlobalTypeResolver(AbstractCompiler compiler) {
      super();
      this.compiler = compiler;
    }
    @Override() public void hotSwapScript(Node scriptRoot, Node originalRoot) {
      patchGlobalTypedScope(compiler, scriptRoot);
    }
    @Override() public void process(Node externs, Node root) {
      if(topScope == null) {
        regenerateGlobalTypedScope(compiler, root.getParent());
      }
      else {
        compiler.getTypeRegistry().resolveTypesInScope(topScope);
      }
    }
  }
  
  abstract static class HotSwapPassFactory extends PassFactory  {
    HotSwapPassFactory(String name, boolean isOneTimePass) {
      super(name, isOneTimePass);
    }
    abstract @Override() protected HotSwapCompilerPass create(AbstractCompiler compiler);
    @Override() HotSwapCompilerPass getHotSwapPass(AbstractCompiler compiler) {
      return this.create(compiler);
    }
  }
}