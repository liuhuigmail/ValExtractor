package com.google.javascript.rhino.jstype;
import static com.google.javascript.rhino.jstype.JSTypeNative.ALL_TYPE;
import static com.google.javascript.rhino.jstype.JSTypeNative.ARRAY_TYPE;
import static com.google.javascript.rhino.jstype.JSTypeNative.NO_TYPE;
import static com.google.javascript.rhino.jstype.JSTypeNative.UNKNOWN_TYPE;
import static com.google.javascript.rhino.jstype.JSTypeNative.VOID_TYPE;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Preconditions;
import com.google.common.collect.ArrayListMultimap;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.LinkedHashMultimap;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.google.common.collect.Multimap;
import com.google.javascript.rhino.ErrorReporter;
import com.google.javascript.rhino.JSDocInfo;
import com.google.javascript.rhino.Node;
import com.google.javascript.rhino.ScriptRuntime;
import com.google.javascript.rhino.Token;
import com.google.javascript.rhino.jstype.RecordTypeBuilder.RecordProperty;
import java.io.Serializable;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class JSTypeRegistry implements Serializable  {
  final private static long serialVersionUID = 1L;
  final private static int PROPERTY_CHECKING_UNION_SIZE = 3000;
  final private transient ErrorReporter reporter;
  final private JSType[] nativeTypes;
  final private Map<String, JSType> namesToTypes;
  final private Set<String> namespaces = new HashSet<String>();
  final private Set<String> nonNullableTypeNames = new HashSet<String>();
  final private Set<String> forwardDeclaredTypes = new HashSet<String>();
  final private Map<String, UnionTypeBuilder> typesIndexedByProperty = Maps.newHashMap();
  final private Map<String, Map<String, ObjectType>> eachRefTypeIndexedByProperty = Maps.newHashMap();
  final private Map<String, JSType> greatestSubtypeByProperty = Maps.newHashMap();
  final private Multimap<String, FunctionType> interfaceToImplementors = LinkedHashMultimap.create();
  final private Multimap<StaticScope<JSType>, NamedType> unresolvedNamedTypes = ArrayListMultimap.create();
  final private Multimap<StaticScope<JSType>, NamedType> resolvedNamedTypes = ArrayListMultimap.create();
  private boolean lastGeneration = true;
  private Map<String, TemplateType> templateTypes = Maps.newHashMap();
  final private boolean tolerateUndefinedValues;
  private ResolveMode resolveMode = ResolveMode.LAZY_NAMES;
  public JSTypeRegistry(ErrorReporter reporter) {
    this(reporter, false);
  }
  public JSTypeRegistry(ErrorReporter reporter, boolean tolerateUndefinedValues) {
    super();
    this.reporter = reporter;
    nativeTypes = new JSType[JSTypeNative.values().length];
    namesToTypes = new HashMap<String, JSType>();
    resetForTypeCheck();
    this.tolerateUndefinedValues = tolerateUndefinedValues;
  }
  ArrowType createArrowType(Node parametersNode) {
    return new ArrowType(this, parametersNode, null);
  }
  ArrowType createArrowType(Node parametersNode, JSType returnType) {
    return new ArrowType(this, parametersNode, returnType);
  }
  public Collection<FunctionType> getDirectImplementors(ObjectType interfaceInstance) {
    return interfaceToImplementors.get(interfaceInstance.getReferenceName());
  }
  public EnumType createEnumType(String name, Node source, JSType elementsType) {
    return new EnumType(this, name, source, elementsType);
  }
  public ErrorReporter getErrorReporter() {
    return reporter;
  }
  public FunctionType createConstructorType(JSType returnType, boolean lastVarArgs, JSType ... parameterTypes) {
    if(lastVarArgs) {
      return createConstructorTypeWithVarArgs(returnType, parameterTypes);
    }
    else {
      return createConstructorType(returnType, parameterTypes);
    }
  }
  public FunctionType createConstructorType(JSType returnType, JSType ... parameterTypes) {
    return createConstructorType(null, null, createParameters(parameterTypes), returnType, null);
  }
  public FunctionType createConstructorType(String name, Node source, Node parameters, JSType returnType, ImmutableList<String> templateKeys) {
    return new FunctionType(this, name, source, createArrowType(parameters, returnType), null, templateKeys, true, false);
  }
  private FunctionType createConstructorTypeWithVarArgs(JSType returnType, JSType ... parameterTypes) {
    return createConstructorType(null, null, createParametersWithVarArgs(parameterTypes), returnType, null);
  }
  public FunctionType createFunctionType(JSType returnType, boolean lastVarArgs, JSType ... parameterTypes) {
    if(lastVarArgs) {
      return createFunctionTypeWithVarArgs(returnType, parameterTypes);
    }
    else {
      return createFunctionType(returnType, parameterTypes);
    }
  }
  public FunctionType createFunctionType(JSType returnType, Node parameters) {
    return new FunctionBuilder(this).withParamsNode(parameters).withReturnType(returnType).build();
  }
  public FunctionType createFunctionType(JSType returnType, JSType ... parameterTypes) {
    return createFunctionType(returnType, createParameters(parameterTypes));
  }
  public FunctionType createFunctionType(JSType returnType, List<JSType> parameterTypes) {
    return createFunctionType(returnType, createParameters(parameterTypes));
  }
  public FunctionType createFunctionTypeWithNewReturnType(FunctionType existingFunctionType, JSType returnType) {
    return new FunctionBuilder(this).copyFromOtherFunction(existingFunctionType).withReturnType(returnType).build();
  }
  public FunctionType createFunctionTypeWithNewThisType(FunctionType existingFunctionType, ObjectType thisType) {
    return new FunctionBuilder(this).copyFromOtherFunction(existingFunctionType).withTypeOfThis(thisType).build();
  }
  public FunctionType createFunctionTypeWithVarArgs(JSType returnType, JSType ... parameterTypes) {
    return createFunctionType(returnType, createParametersWithVarArgs(parameterTypes));
  }
  public FunctionType createFunctionTypeWithVarArgs(JSType returnType, List<JSType> parameterTypes) {
    return createFunctionType(returnType, createParametersWithVarArgs(parameterTypes));
  }
  public FunctionType createInterfaceType(String name, Node source) {
    return FunctionType.forInterface(this, name, source);
  }
  private FunctionType createNativeFunctionType(JSType returnType, Node parameters) {
    return new FunctionBuilder(this).withParamsNode(parameters).withReturnType(returnType).forNativeType().build();
  }
  private FunctionType createNativeFunctionTypeWithVarArgs(JSType returnType, JSType ... parameterTypes) {
    return createNativeFunctionType(returnType, createParametersWithVarArgs(parameterTypes));
  }
  public FunctionType getNativeFunctionType(JSTypeNative typeId) {
    return (FunctionType)getNativeType(typeId);
  }
  public Iterable<JSType> getTypesWithProperty(String propertyName) {
    if(typesIndexedByProperty.containsKey(propertyName)) {
      return typesIndexedByProperty.get(propertyName).getAlternates();
    }
    else {
      return ImmutableList.of();
    }
  }
  public Iterable<ObjectType> getEachReferenceTypeWithProperty(String propertyName) {
    if(eachRefTypeIndexedByProperty.containsKey(propertyName)) {
      return eachRefTypeIndexedByProperty.get(propertyName).values();
    }
    else {
      return ImmutableList.of();
    }
  }
  public JSType createDefaultObjectUnion(JSType type) {
    if(type.isTemplateType()) {
      return type;
    }
    else {
      return shouldTolerateUndefinedValues() ? createOptionalNullableType(type) : createNullableType(type);
    }
  }
  public JSType createFromTypeNodes(Node n, String sourceName, StaticScope<JSType> scope) {
    if(resolveMode == ResolveMode.LAZY_EXPRESSIONS) {
      boolean hasNames = hasTypeName(n);
      if(hasNames) {
        return new UnresolvedTypeExpression(this, n, sourceName);
      }
    }
    return createFromTypeNodesInternal(n, sourceName, scope);
  }
  private JSType createFromTypeNodesInternal(Node n, String sourceName, StaticScope<JSType> scope) {
    switch (n.getType()){
      case Token.LC:
      return createRecordTypeFromNodes(n.getFirstChild(), sourceName, scope);
      case Token.BANG:
      return createFromTypeNodesInternal(n.getFirstChild(), sourceName, scope).restrictByNotNullOrUndefined();
      case Token.QMARK:
      Node firstChild = n.getFirstChild();
      if(firstChild == null) {
        return getNativeType(UNKNOWN_TYPE);
      }
      return createDefaultObjectUnion(createFromTypeNodesInternal(firstChild, sourceName, scope));
      case Token.EQUALS:
      return createOptionalType(createFromTypeNodesInternal(n.getFirstChild(), sourceName, scope));
      case Token.ELLIPSIS:
      return createOptionalType(createFromTypeNodesInternal(n.getFirstChild(), sourceName, scope));
      case Token.STAR:
      return getNativeType(ALL_TYPE);
      case Token.LB:
      return getNativeType(ARRAY_TYPE);
      case Token.PIPE:
      UnionTypeBuilder builder = new UnionTypeBuilder(this);
      for(com.google.javascript.rhino.Node child = n.getFirstChild(); child != null; child = child.getNext()) {
        builder.addAlternate(createFromTypeNodesInternal(child, sourceName, scope));
      }
      return builder.build();
      case Token.EMPTY:
      return getNativeType(UNKNOWN_TYPE);
      case Token.VOID:
      return getNativeType(VOID_TYPE);
      case Token.STRING:
      JSType namedType = getType(scope, n.getString(), sourceName, n.getLineno(), n.getCharno());
      if(resolveMode != ResolveMode.LAZY_NAMES) {
        namedType = namedType.resolveInternal(reporter, scope);
      }
      if((namedType instanceof ObjectType) && !(nonNullableTypeNames.contains(n.getString()))) {
        Node typeList = n.getFirstChild();
        if(typeList != null && ("Array".equals(n.getString()) || "Object".equals(n.getString()))) {
          JSType parameterType = createFromTypeNodesInternal(typeList.getLastChild(), sourceName, scope);
          namedType = new ParameterizedType(this, (ObjectType)namedType, parameterType);
          if(typeList.hasMoreThanOneChild()) {
            JSType indexType = createFromTypeNodesInternal(typeList.getFirstChild(), sourceName, scope);
            namedType = new IndexedType(this, (ObjectType)namedType, indexType);
          }
        }
        return createDefaultObjectUnion(namedType);
      }
      else {
        return namedType;
      }
      case Token.FUNCTION:
      ObjectType thisType = null;
      boolean isConstructor = false;
      Node current = n.getFirstChild();
      if(current.getType() == Token.THIS || current.getType() == Token.NEW) {
        Node contextNode = current.getFirstChild();
        thisType = ObjectType.cast(createFromTypeNodesInternal(contextNode, sourceName, scope).restrictByNotNullOrUndefined());
        if(thisType == null) {
          reporter.warning(ScriptRuntime.getMessage0(current.getType() == Token.THIS ? "msg.jsdoc.function.thisnotobject" : "msg.jsdoc.function.newnotobject"), sourceName, contextNode.getLineno(), contextNode.getCharno());
        }
        isConstructor = current.getType() == Token.NEW;
        current = current.getNext();
      }
      FunctionParamBuilder paramBuilder = new FunctionParamBuilder(this);
      if(current.getType() == Token.PARAM_LIST) {
        Node args = current.getFirstChild();
        for(com.google.javascript.rhino.Node arg = current.getFirstChild(); arg != null; arg = arg.getNext()) {
          if(arg.getType() == Token.ELLIPSIS) {
            if(arg.getChildCount() == 0) {
              paramBuilder.addVarArgs(getNativeType(UNKNOWN_TYPE));
            }
            else {
              paramBuilder.addVarArgs(createFromTypeNodesInternal(arg.getFirstChild(), sourceName, scope));
            }
          }
          else {
            JSType type = createFromTypeNodesInternal(arg, sourceName, scope);
            if(arg.getType() == Token.EQUALS) {
              boolean addSuccess = paramBuilder.addOptionalParams(type);
              if(!addSuccess) {
                reporter.warning(ScriptRuntime.getMessage0("msg.jsdoc.function.varargs"), sourceName, arg.getLineno(), arg.getCharno());
              }
            }
            else {
              paramBuilder.addRequiredParams(type);
            }
          }
        }
        current = current.getNext();
      }
      JSType returnType = createFromTypeNodesInternal(current, sourceName, scope);
      return new FunctionBuilder(this).withParams(paramBuilder).withReturnType(returnType).withTypeOfThis(thisType).setIsConstructor(isConstructor).build();
    }
    throw new IllegalStateException("Unexpected node in type expression: " + n.toString());
  }
  public JSType createFunctionType(ObjectType instanceType, JSType returnType, List<JSType> parameterTypes) {
    return new FunctionBuilder(this).withParamsNode(createParameters(parameterTypes)).withReturnType(returnType).withTypeOfThis(instanceType).build();
  }
  public JSType createFunctionTypeWithVarArgs(ObjectType instanceType, JSType returnType, List<JSType> parameterTypes) {
    return new FunctionBuilder(this).withParamsNode(createParametersWithVarArgs(parameterTypes)).withReturnType(returnType).withTypeOfThis(instanceType).build();
  }
  @VisibleForTesting() public JSType createNamedType(String reference, String sourceName, int lineno, int charno) {
    return new NamedType(this, reference, sourceName, lineno, charno);
  }
  public JSType createNullableType(JSType type) {
    return createUnionType(type, getNativeType(JSTypeNative.NULL_TYPE));
  }
  public JSType createOptionalNullableType(JSType type) {
    return createUnionType(type, getNativeType(JSTypeNative.VOID_TYPE), getNativeType(JSTypeNative.NULL_TYPE));
  }
  public JSType createOptionalType(JSType type) {
    if(type instanceof UnknownType || type.isAllType()) {
      return type;
    }
    else {
      return createUnionType(type, getNativeType(JSTypeNative.VOID_TYPE));
    }
  }
  private JSType createRecordTypeFromNodes(Node n, String sourceName, StaticScope<JSType> scope) {
    RecordTypeBuilder builder = new RecordTypeBuilder(this);
    for(com.google.javascript.rhino.Node fieldTypeNode = n.getFirstChild(); fieldTypeNode != null; fieldTypeNode = fieldTypeNode.getNext()) {
      Node fieldNameNode = fieldTypeNode;
      boolean hasType = false;
      if(fieldTypeNode.getType() == Token.COLON) {
        fieldNameNode = fieldTypeNode.getFirstChild();
        hasType = true;
      }
      String fieldName = fieldNameNode.getString();
      if(fieldName.startsWith("\'") || fieldName.startsWith("\"")) {
        fieldName = fieldName.substring(1, fieldName.length() - 1);
      }
      JSType fieldType = null;
      if(hasType) {
        fieldType = createFromTypeNodesInternal(fieldTypeNode.getLastChild(), sourceName, scope);
      }
      else {
        fieldType = getNativeType(JSTypeNative.UNKNOWN_TYPE);
      }
      if(builder.addProperty(fieldName, fieldType, fieldNameNode) == null) {
        reporter.warning("Duplicate record field " + fieldName, sourceName, n.getLineno(), fieldNameNode.getCharno());
      }
    }
    return builder.build();
  }
  public JSType createTemplatizedType(JSType baseType, ImmutableList<JSType> templatizedTypes) {
    if(baseType instanceof InstanceObjectType) {
      ObjectType baseObjType = baseType.toObjectType();
      return new InstanceObjectType(this, baseObjType.getConstructor(), baseObjType.isNativeObjectType(), templatizedTypes);
    }
    else {
      throw new IllegalArgumentException("Only instance object types can be templatized");
    }
  }
  public JSType createUnionType(JSTypeNative ... variants) {
    UnionTypeBuilder builder = new UnionTypeBuilder(this);
    for (JSTypeNative typeId : variants) {
      builder.addAlternate(getNativeType(typeId));
    }
    return builder.build();
  }
  public JSType createUnionType(JSType ... variants) {
    UnionTypeBuilder builder = new UnionTypeBuilder(this);
    for (JSType type : variants) {
      builder.addAlternate(type);
    }
    return builder.build();
  }
  public JSType getGreatestSubtypeWithProperty(JSType type, String propertyName) {
    if(greatestSubtypeByProperty.containsKey(propertyName)) {
      return greatestSubtypeByProperty.get(propertyName).getGreatestSubtype(type);
    }
    if(typesIndexedByProperty.containsKey(propertyName)) {
      JSType built = typesIndexedByProperty.get(propertyName).build();
      greatestSubtypeByProperty.put(propertyName, built);
      return built.getGreatestSubtype(type);
    }
    return getNativeType(NO_TYPE);
  }
  public JSType getNativeType(JSTypeNative typeId) {
    return nativeTypes[typeId.ordinal()];
  }
  public JSType getType(StaticScope<JSType> scope, String jsTypeName, String sourceName, int lineno, int charno) {
    JSType type = getType(jsTypeName);
    if(type == null) {
      NamedType namedType = new NamedType(this, jsTypeName, sourceName, lineno, charno);
      unresolvedNamedTypes.put(scope, namedType);
      type = namedType;
    }
    return type;
  }
  public JSType getType(String jsTypeName) {
    TemplateType templateType = templateTypes.get(jsTypeName);
    if(templateType != null) {
      return templateType;
    }
    return namesToTypes.get(jsTypeName);
  }
  private static List<ObjectType> getSuperStack(ObjectType a) {
    List<ObjectType> stack = Lists.newArrayListWithExpectedSize(5);
    for(com.google.javascript.rhino.jstype.ObjectType current = a; current != null; current = current.getImplicitPrototype()) {
      stack.add(current);
    }
    return stack;
  }
  public Node createOptionalParameters(JSType ... parameterTypes) {
    FunctionParamBuilder builder = new FunctionParamBuilder(this);
    builder.addOptionalParams(parameterTypes);
    return builder.build();
  }
  private Node createParameters(boolean lastVarArgs, JSType ... parameterTypes) {
    FunctionParamBuilder builder = new FunctionParamBuilder(this);
    int max = parameterTypes.length - 1;
    for(int i = 0; i <= max; i++) {
      if(lastVarArgs && i == max) {
        builder.addVarArgs(parameterTypes[i]);
      }
      else {
        builder.addRequiredParams(parameterTypes[i]);
      }
    }
    return builder.build();
  }
  public Node createParameters(JSType ... parameterTypes) {
    return createParameters(false, parameterTypes);
  }
  public Node createParameters(List<JSType> parameterTypes) {
    return createParameters(parameterTypes.toArray(new JSType[parameterTypes.size()]));
  }
  public Node createParametersWithVarArgs(JSType ... parameterTypes) {
    return createParameters(true, parameterTypes);
  }
  public Node createParametersWithVarArgs(List<JSType> parameterTypes) {
    return createParametersWithVarArgs(parameterTypes.toArray(new JSType[parameterTypes.size()]));
  }
  public ObjectType createAnonymousObjectType(JSDocInfo info) {
    PrototypeObjectType type = new PrototypeObjectType(this, null, null);
    type.setPrettyPrint(true);
    type.setJSDocInfo(info);
    return type;
  }
  ObjectType createNativeAnonymousObjectType() {
    PrototypeObjectType type = new PrototypeObjectType(this, null, null, true, null, null);
    type.setPrettyPrint(true);
    return type;
  }
  public ObjectType createObjectType(ObjectType implicitPrototype) {
    return createObjectType(null, null, implicitPrototype);
  }
  public ObjectType createObjectType(String name, Node n, ObjectType implicitPrototype) {
    return new PrototypeObjectType(this, name, implicitPrototype);
  }
  ObjectType findCommonSuperObject(ObjectType a, ObjectType b) {
    List<ObjectType> stackA = getSuperStack(a);
    List<ObjectType> stackB = getSuperStack(b);
    ObjectType result = getNativeObjectType(JSTypeNative.OBJECT_TYPE);
    while(!stackA.isEmpty() && !stackB.isEmpty()){
      ObjectType currentA = stackA.remove(stackA.size() - 1);
      ObjectType currentB = stackB.remove(stackB.size() - 1);
      if(currentA.isEquivalentTo(currentB)) {
        result = currentA;
      }
      else {
        return result;
      }
    }
    return result;
  }
  public ObjectType getNativeObjectType(JSTypeNative typeId) {
    return (ObjectType)getNativeType(typeId);
  }
  public ParameterizedType createParameterizedType(ObjectType objectType, JSType parameterType) {
    return new ParameterizedType(this, objectType, parameterType);
  }
  public RecordType createRecordType(Map<String, RecordProperty> properties) {
    return new RecordType(this, properties);
  }
  ResolveMode getResolveMode() {
    return resolveMode;
  }
  public boolean canPropertyBeDefined(JSType type, String propertyName) {
    if(typesIndexedByProperty.containsKey(propertyName)) {
      for (JSType alt : typesIndexedByProperty.get(propertyName).getAlternates()) {
        JSType greatestSubtype = alt.getGreatestSubtype(type);
        if(!greatestSubtype.isEmptyType()) {
          RecordType maybeRecordType = greatestSubtype.toMaybeRecordType();
          if(maybeRecordType != null && maybeRecordType.isSynthetic()) {
            continue ;
          }
          return true;
        }
      }
    }
    return false;
  }
  public boolean declareType(String name, JSType t) {
    if(namesToTypes.containsKey(name)) {
      return false;
    }
    register(t, name);
    return true;
  }
  public boolean hasNamespace(String name) {
    return namespaces.contains(name);
  }
  private boolean hasTypeName(Node n) {
    if(n.getType() == Token.STRING) {
      return true;
    }
    for(com.google.javascript.rhino.Node child = n.getFirstChild(); child != null; child = child.getNext()) {
      if(hasTypeName(child)) {
        return true;
      }
    }
    return false;
  }
  public boolean isForwardDeclaredType(String name) {
    return forwardDeclaredTypes.contains(name);
  }
  boolean isLastGeneration() {
    return lastGeneration;
  }
  public boolean resetImplicitPrototype(JSType type, ObjectType newImplicitProto) {
    if(type instanceof PrototypeObjectType) {
      PrototypeObjectType poType = (PrototypeObjectType)type;
      poType.clearCachedValues();
      poType.setImplicitPrototype(newImplicitProto);
      return true;
    }
    return false;
  }
  public boolean shouldTolerateUndefinedValues() {
    return tolerateUndefinedValues;
  }
  private void addReferenceTypeIndexedByProperty(String propertyName, JSType type) {
    if(type instanceof ObjectType && ((ObjectType)type).hasReferenceName()) {
      Map<String, ObjectType> typeSet = eachRefTypeIndexedByProperty.get(propertyName);
      if(typeSet == null) {
        typeSet = Maps.newHashMap();
        eachRefTypeIndexedByProperty.put(propertyName, typeSet);
      }
      ObjectType objType = (ObjectType)type;
      typeSet.put(objType.getReferenceName(), objType);
    }
    else 
      if(type instanceof NamedType) {
        addReferenceTypeIndexedByProperty(propertyName, ((NamedType)type).getReferencedType());
      }
      else 
        if(type.isUnionType()) {
          for (JSType alternate : type.toMaybeUnionType().getAlternates()) {
            addReferenceTypeIndexedByProperty(propertyName, alternate);
          }
        }
  }
  public void clearNamedTypes() {
    resolvedNamedTypes.clear();
    unresolvedNamedTypes.clear();
  }
  public void clearTemplateTypeNames() {
    templateTypes.clear();
  }
  public void forwardDeclareType(String name) {
    forwardDeclaredTypes.add(name);
  }
  public void identifyNonNullableName(String name) {
    Preconditions.checkNotNull(name);
    nonNullableTypeNames.add(name);
  }
  public void incrementGeneration() {
    for (NamedType type : resolvedNamedTypes.values()) {
      type.clearResolved();
    }
    unresolvedNamedTypes.putAll(resolvedNamedTypes);
    resolvedNamedTypes.clear();
  }
  private void initializeBuiltInTypes() {
    BooleanType BOOLEAN_TYPE = new BooleanType(this);
    registerNativeType(JSTypeNative.BOOLEAN_TYPE, BOOLEAN_TYPE);
    NullType NULL_TYPE = new NullType(this);
    registerNativeType(JSTypeNative.NULL_TYPE, NULL_TYPE);
    NumberType NUMBER_TYPE = new NumberType(this);
    registerNativeType(JSTypeNative.NUMBER_TYPE, NUMBER_TYPE);
    StringType STRING_TYPE = new StringType(this);
    registerNativeType(JSTypeNative.STRING_TYPE, STRING_TYPE);
    UnknownType UNKNOWN_TYPE = new UnknownType(this, false);
    registerNativeType(JSTypeNative.UNKNOWN_TYPE, UNKNOWN_TYPE);
    UnknownType checkedUnknownType = new UnknownType(this, true);
    registerNativeType(JSTypeNative.CHECKED_UNKNOWN_TYPE, checkedUnknownType);
    VoidType VOID_TYPE = new VoidType(this);
    registerNativeType(JSTypeNative.VOID_TYPE, VOID_TYPE);
    AllType ALL_TYPE = new AllType(this);
    registerNativeType(JSTypeNative.ALL_TYPE, ALL_TYPE);
    PrototypeObjectType TOP_LEVEL_PROTOTYPE = new PrototypeObjectType(this, null, null, true, null, null);
    registerNativeType(JSTypeNative.TOP_LEVEL_PROTOTYPE, TOP_LEVEL_PROTOTYPE);
    FunctionType OBJECT_FUNCTION_TYPE = new FunctionType(this, "Object", null, createArrowType(createOptionalParameters(ALL_TYPE), UNKNOWN_TYPE), null, null, true, true);
    OBJECT_FUNCTION_TYPE.setPrototype(TOP_LEVEL_PROTOTYPE, null);
    registerNativeType(JSTypeNative.OBJECT_FUNCTION_TYPE, OBJECT_FUNCTION_TYPE);
    ObjectType OBJECT_TYPE = OBJECT_FUNCTION_TYPE.getInstanceType();
    registerNativeType(JSTypeNative.OBJECT_TYPE, OBJECT_TYPE);
    ObjectType OBJECT_PROTOTYPE = OBJECT_FUNCTION_TYPE.getPrototype();
    registerNativeType(JSTypeNative.OBJECT_PROTOTYPE, OBJECT_PROTOTYPE);
    FunctionType FUNCTION_FUNCTION_TYPE = new FunctionType(this, "Function", null, createArrowType(createParametersWithVarArgs(ALL_TYPE), UNKNOWN_TYPE), null, null, true, true);
    FUNCTION_FUNCTION_TYPE.setPrototypeBasedOn(OBJECT_TYPE);
    registerNativeType(JSTypeNative.FUNCTION_FUNCTION_TYPE, FUNCTION_FUNCTION_TYPE);
    ObjectType FUNCTION_PROTOTYPE = FUNCTION_FUNCTION_TYPE.getPrototype();
    registerNativeType(JSTypeNative.FUNCTION_PROTOTYPE, FUNCTION_PROTOTYPE);
    NoType NO_TYPE = new NoType(this);
    registerNativeType(JSTypeNative.NO_TYPE, NO_TYPE);
    NoObjectType NO_OBJECT_TYPE = new NoObjectType(this);
    registerNativeType(JSTypeNative.NO_OBJECT_TYPE, NO_OBJECT_TYPE);
    NoObjectType NO_RESOLVED_TYPE = new NoResolvedType(this);
    registerNativeType(JSTypeNative.NO_RESOLVED_TYPE, NO_RESOLVED_TYPE);
    FunctionType ARRAY_FUNCTION_TYPE = new FunctionType(this, "Array", null, createArrowType(createParametersWithVarArgs(ALL_TYPE), null), null, null, true, true);
    ARRAY_FUNCTION_TYPE.getInternalArrowType().returnType = ARRAY_FUNCTION_TYPE.getInstanceType();
    ObjectType arrayPrototype = ARRAY_FUNCTION_TYPE.getPrototype();
    registerNativeType(JSTypeNative.ARRAY_FUNCTION_TYPE, ARRAY_FUNCTION_TYPE);
    ObjectType ARRAY_TYPE = ARRAY_FUNCTION_TYPE.getInstanceType();
    registerNativeType(JSTypeNative.ARRAY_TYPE, ARRAY_TYPE);
    FunctionType BOOLEAN_OBJECT_FUNCTION_TYPE = new FunctionType(this, "Boolean", null, createArrowType(createOptionalParameters(ALL_TYPE), BOOLEAN_TYPE), null, null, true, true);
    ObjectType booleanPrototype = BOOLEAN_OBJECT_FUNCTION_TYPE.getPrototype();
    registerNativeType(JSTypeNative.BOOLEAN_OBJECT_FUNCTION_TYPE, BOOLEAN_OBJECT_FUNCTION_TYPE);
    ObjectType BOOLEAN_OBJECT_TYPE = BOOLEAN_OBJECT_FUNCTION_TYPE.getInstanceType();
    registerNativeType(JSTypeNative.BOOLEAN_OBJECT_TYPE, BOOLEAN_OBJECT_TYPE);
    FunctionType DATE_FUNCTION_TYPE = new FunctionType(this, "Date", null, createArrowType(createOptionalParameters(UNKNOWN_TYPE, UNKNOWN_TYPE, UNKNOWN_TYPE, UNKNOWN_TYPE, UNKNOWN_TYPE, UNKNOWN_TYPE, UNKNOWN_TYPE), STRING_TYPE), null, null, true, true);
    ObjectType datePrototype = DATE_FUNCTION_TYPE.getPrototype();
    registerNativeType(JSTypeNative.DATE_FUNCTION_TYPE, DATE_FUNCTION_TYPE);
    ObjectType DATE_TYPE = DATE_FUNCTION_TYPE.getInstanceType();
    registerNativeType(JSTypeNative.DATE_TYPE, DATE_TYPE);
    FunctionType ERROR_FUNCTION_TYPE = new ErrorFunctionType(this, "Error");
    registerNativeType(JSTypeNative.ERROR_FUNCTION_TYPE, ERROR_FUNCTION_TYPE);
    ObjectType ERROR_TYPE = ERROR_FUNCTION_TYPE.getInstanceType();
    registerNativeType(JSTypeNative.ERROR_TYPE, ERROR_TYPE);
    FunctionType EVAL_ERROR_FUNCTION_TYPE = new ErrorFunctionType(this, "EvalError");
    EVAL_ERROR_FUNCTION_TYPE.setPrototypeBasedOn(ERROR_TYPE);
    registerNativeType(JSTypeNative.EVAL_ERROR_FUNCTION_TYPE, EVAL_ERROR_FUNCTION_TYPE);
    ObjectType EVAL_ERROR_TYPE = EVAL_ERROR_FUNCTION_TYPE.getInstanceType();
    registerNativeType(JSTypeNative.EVAL_ERROR_TYPE, EVAL_ERROR_TYPE);
    FunctionType RANGE_ERROR_FUNCTION_TYPE = new ErrorFunctionType(this, "RangeError");
    RANGE_ERROR_FUNCTION_TYPE.setPrototypeBasedOn(ERROR_TYPE);
    registerNativeType(JSTypeNative.RANGE_ERROR_FUNCTION_TYPE, RANGE_ERROR_FUNCTION_TYPE);
    ObjectType RANGE_ERROR_TYPE = RANGE_ERROR_FUNCTION_TYPE.getInstanceType();
    registerNativeType(JSTypeNative.RANGE_ERROR_TYPE, RANGE_ERROR_TYPE);
    FunctionType REFERENCE_ERROR_FUNCTION_TYPE = new ErrorFunctionType(this, "ReferenceError");
    REFERENCE_ERROR_FUNCTION_TYPE.setPrototypeBasedOn(ERROR_TYPE);
    registerNativeType(JSTypeNative.REFERENCE_ERROR_FUNCTION_TYPE, REFERENCE_ERROR_FUNCTION_TYPE);
    ObjectType REFERENCE_ERROR_TYPE = REFERENCE_ERROR_FUNCTION_TYPE.getInstanceType();
    registerNativeType(JSTypeNative.REFERENCE_ERROR_TYPE, REFERENCE_ERROR_TYPE);
    FunctionType SYNTAX_ERROR_FUNCTION_TYPE = new ErrorFunctionType(this, "SyntaxError");
    SYNTAX_ERROR_FUNCTION_TYPE.setPrototypeBasedOn(ERROR_TYPE);
    registerNativeType(JSTypeNative.SYNTAX_ERROR_FUNCTION_TYPE, SYNTAX_ERROR_FUNCTION_TYPE);
    ObjectType SYNTAX_ERROR_TYPE = SYNTAX_ERROR_FUNCTION_TYPE.getInstanceType();
    registerNativeType(JSTypeNative.SYNTAX_ERROR_TYPE, SYNTAX_ERROR_TYPE);
    FunctionType TYPE_ERROR_FUNCTION_TYPE = new ErrorFunctionType(this, "TypeError");
    TYPE_ERROR_FUNCTION_TYPE.setPrototypeBasedOn(ERROR_TYPE);
    registerNativeType(JSTypeNative.TYPE_ERROR_FUNCTION_TYPE, TYPE_ERROR_FUNCTION_TYPE);
    ObjectType TYPE_ERROR_TYPE = TYPE_ERROR_FUNCTION_TYPE.getInstanceType();
    registerNativeType(JSTypeNative.TYPE_ERROR_TYPE, TYPE_ERROR_TYPE);
    FunctionType URI_ERROR_FUNCTION_TYPE = new ErrorFunctionType(this, "URIError");
    URI_ERROR_FUNCTION_TYPE.setPrototypeBasedOn(ERROR_TYPE);
    registerNativeType(JSTypeNative.URI_ERROR_FUNCTION_TYPE, URI_ERROR_FUNCTION_TYPE);
    ObjectType URI_ERROR_TYPE = URI_ERROR_FUNCTION_TYPE.getInstanceType();
    registerNativeType(JSTypeNative.URI_ERROR_TYPE, URI_ERROR_TYPE);
    FunctionType NUMBER_OBJECT_FUNCTION_TYPE = new FunctionType(this, "Number", null, createArrowType(createOptionalParameters(ALL_TYPE), NUMBER_TYPE), null, null, true, true);
    ObjectType numberPrototype = NUMBER_OBJECT_FUNCTION_TYPE.getPrototype();
    registerNativeType(JSTypeNative.NUMBER_OBJECT_FUNCTION_TYPE, NUMBER_OBJECT_FUNCTION_TYPE);
    ObjectType NUMBER_OBJECT_TYPE = NUMBER_OBJECT_FUNCTION_TYPE.getInstanceType();
    registerNativeType(JSTypeNative.NUMBER_OBJECT_TYPE, NUMBER_OBJECT_TYPE);
    FunctionType REGEXP_FUNCTION_TYPE = new FunctionType(this, "RegExp", null, createArrowType(createOptionalParameters(ALL_TYPE, ALL_TYPE)), null, null, true, true);
    REGEXP_FUNCTION_TYPE.getInternalArrowType().returnType = REGEXP_FUNCTION_TYPE.getInstanceType();
    ObjectType regexpPrototype = REGEXP_FUNCTION_TYPE.getPrototype();
    registerNativeType(JSTypeNative.REGEXP_FUNCTION_TYPE, REGEXP_FUNCTION_TYPE);
    ObjectType REGEXP_TYPE = REGEXP_FUNCTION_TYPE.getInstanceType();
    registerNativeType(JSTypeNative.REGEXP_TYPE, REGEXP_TYPE);
    FunctionType STRING_OBJECT_FUNCTION_TYPE = new FunctionType(this, "String", null, createArrowType(createOptionalParameters(ALL_TYPE), STRING_TYPE), null, null, true, true);
    ObjectType stringPrototype = STRING_OBJECT_FUNCTION_TYPE.getPrototype();
    registerNativeType(JSTypeNative.STRING_OBJECT_FUNCTION_TYPE, STRING_OBJECT_FUNCTION_TYPE);
    ObjectType STRING_OBJECT_TYPE = STRING_OBJECT_FUNCTION_TYPE.getInstanceType();
    registerNativeType(JSTypeNative.STRING_OBJECT_TYPE, STRING_OBJECT_TYPE);
    JSType NULL_VOID = createUnionType(NULL_TYPE, VOID_TYPE);
    registerNativeType(JSTypeNative.NULL_VOID, NULL_VOID);
    JSType OBJECT_NUMBER_STRING = createUnionType(OBJECT_TYPE, NUMBER_TYPE, STRING_TYPE);
    registerNativeType(JSTypeNative.OBJECT_NUMBER_STRING, OBJECT_NUMBER_STRING);
    JSType OBJECT_NUMBER_STRING_BOOLEAN = createUnionType(OBJECT_TYPE, NUMBER_TYPE, STRING_TYPE, BOOLEAN_TYPE);
    registerNativeType(JSTypeNative.OBJECT_NUMBER_STRING_BOOLEAN, OBJECT_NUMBER_STRING_BOOLEAN);
    JSType NUMBER_STRING_BOOLEAN = createUnionType(NUMBER_TYPE, STRING_TYPE, BOOLEAN_TYPE);
    registerNativeType(JSTypeNative.NUMBER_STRING_BOOLEAN, NUMBER_STRING_BOOLEAN);
    JSType NUMBER_STRING = createUnionType(NUMBER_TYPE, STRING_TYPE);
    registerNativeType(JSTypeNative.NUMBER_STRING, NUMBER_STRING);
    JSType STRING_VALUE_OR_OBJECT_TYPE = createUnionType(STRING_OBJECT_TYPE, STRING_TYPE);
    registerNativeType(JSTypeNative.STRING_VALUE_OR_OBJECT_TYPE, STRING_VALUE_OR_OBJECT_TYPE);
    JSType NUMBER_VALUE_OR_OBJECT_TYPE = createUnionType(NUMBER_OBJECT_TYPE, NUMBER_TYPE);
    registerNativeType(JSTypeNative.NUMBER_VALUE_OR_OBJECT_TYPE, NUMBER_VALUE_OR_OBJECT_TYPE);
    FunctionType U2U_FUNCTION_TYPE = createFunctionType(UNKNOWN_TYPE, true, UNKNOWN_TYPE);
    registerNativeType(JSTypeNative.U2U_FUNCTION_TYPE, U2U_FUNCTION_TYPE);
    FunctionType U2U_CONSTRUCTOR_TYPE = new FunctionType(this, "Function", null, createArrowType(createParametersWithVarArgs(UNKNOWN_TYPE), UNKNOWN_TYPE), UNKNOWN_TYPE, null, true, true) {
        final private static long serialVersionUID = 1L;
        @Override() public FunctionType getConstructor() {
          return registry.getNativeFunctionType(JSTypeNative.FUNCTION_FUNCTION_TYPE);
        }
    };
    registerNativeType(JSTypeNative.U2U_CONSTRUCTOR_TYPE, U2U_CONSTRUCTOR_TYPE);
    registerNativeType(JSTypeNative.FUNCTION_INSTANCE_TYPE, U2U_CONSTRUCTOR_TYPE);
    FUNCTION_FUNCTION_TYPE.setInstanceType(U2U_CONSTRUCTOR_TYPE);
    U2U_CONSTRUCTOR_TYPE.setImplicitPrototype(FUNCTION_PROTOTYPE);
    FunctionType LEAST_FUNCTION_TYPE = createNativeFunctionTypeWithVarArgs(NO_TYPE, ALL_TYPE);
    registerNativeType(JSTypeNative.LEAST_FUNCTION_TYPE, LEAST_FUNCTION_TYPE);
    FunctionType GLOBAL_THIS_CTOR = new FunctionType(this, "global this", null, createArrowType(createParameters(false, ALL_TYPE), NUMBER_TYPE), null, null, true, true);
    ObjectType GLOBAL_THIS = GLOBAL_THIS_CTOR.getInstanceType();
    registerNativeType(JSTypeNative.GLOBAL_THIS, GLOBAL_THIS);
    FunctionType GREATEST_FUNCTION_TYPE = createNativeFunctionTypeWithVarArgs(ALL_TYPE, NO_TYPE);
    registerNativeType(JSTypeNative.GREATEST_FUNCTION_TYPE, GREATEST_FUNCTION_TYPE);
    registerPropertyOnType("prototype", OBJECT_FUNCTION_TYPE);
  }
  private void initializeRegistry() {
    register(getNativeType(JSTypeNative.ARRAY_TYPE));
    register(getNativeType(JSTypeNative.BOOLEAN_OBJECT_TYPE));
    register(getNativeType(JSTypeNative.BOOLEAN_TYPE));
    register(getNativeType(JSTypeNative.DATE_TYPE));
    JSType var_2664 = getNativeType(JSTypeNative.NULL_TYPE);
    register(var_2664);
    register(getNativeType(JSTypeNative.NULL_TYPE), "Null");
    register(getNativeType(JSTypeNative.NUMBER_OBJECT_TYPE));
    register(getNativeType(JSTypeNative.NUMBER_TYPE));
    register(getNativeType(JSTypeNative.OBJECT_TYPE));
    register(getNativeType(JSTypeNative.ERROR_TYPE));
    register(getNativeType(JSTypeNative.URI_ERROR_TYPE));
    register(getNativeType(JSTypeNative.EVAL_ERROR_TYPE));
    register(getNativeType(JSTypeNative.TYPE_ERROR_TYPE));
    register(getNativeType(JSTypeNative.RANGE_ERROR_TYPE));
    register(getNativeType(JSTypeNative.REFERENCE_ERROR_TYPE));
    register(getNativeType(JSTypeNative.SYNTAX_ERROR_TYPE));
    register(getNativeType(JSTypeNative.REGEXP_TYPE));
    register(getNativeType(JSTypeNative.STRING_OBJECT_TYPE));
    register(getNativeType(JSTypeNative.STRING_TYPE));
    register(getNativeType(JSTypeNative.VOID_TYPE));
    register(getNativeType(JSTypeNative.VOID_TYPE), "Undefined");
    register(getNativeType(JSTypeNative.VOID_TYPE), "void");
    register(getNativeType(JSTypeNative.FUNCTION_INSTANCE_TYPE), "Function");
  }
  public void overwriteDeclaredType(String name, JSType t) {
    Preconditions.checkState(namesToTypes.containsKey(name));
    register(t, name);
  }
  private void register(JSType type) {
    register(type, type.toString());
  }
  private void register(JSType type, String name) {
    Preconditions.checkArgument(!name.contains("<"), "Type names cannot contain template annotations.");
    namesToTypes.put(name, type);
    while(name.indexOf('.') > 0){
      name = name.substring(0, name.lastIndexOf('.'));
      namespaces.add(name);
    }
  }
  private void registerNativeType(JSTypeNative typeId, JSType type) {
    nativeTypes[typeId.ordinal()] = type;
  }
  public void registerPropertyOnType(String propertyName, JSType type) {
    UnionTypeBuilder typeSet = typesIndexedByProperty.get(propertyName);
    if(typeSet == null) {
      typeSet = new UnionTypeBuilder(this, PROPERTY_CHECKING_UNION_SIZE);
      typesIndexedByProperty.put(propertyName, typeSet);
    }
    typeSet.addAlternate(type);
    addReferenceTypeIndexedByProperty(propertyName, type);
    greatestSubtypeByProperty.remove(propertyName);
  }
  void registerTypeImplementingInterface(FunctionType type, ObjectType interfaceInstance) {
    interfaceToImplementors.put(interfaceInstance.getReferenceName(), type);
  }
  public void resetForTypeCheck() {
    typesIndexedByProperty.clear();
    eachRefTypeIndexedByProperty.clear();
    initializeBuiltInTypes();
    namesToTypes.clear();
    namespaces.clear();
    initializeRegistry();
  }
  public void resolveTypesInScope(StaticScope<JSType> scope) {
    for (NamedType type : unresolvedNamedTypes.get(scope)) {
      type.resolve(reporter, scope);
    }
    resolvedNamedTypes.putAll(scope, unresolvedNamedTypes.removeAll(scope));
    if(scope != null && scope.getParentScope() == null) {
      PrototypeObjectType globalThis = (PrototypeObjectType)getNativeType(JSTypeNative.GLOBAL_THIS);
      JSType windowType = getType("Window");
      if(globalThis.isUnknownType()) {
        ObjectType windowObjType = ObjectType.cast(windowType);
        if(windowObjType != null) {
          globalThis.setImplicitPrototype(windowObjType);
        }
        else {
          globalThis.setImplicitPrototype(getNativeObjectType(JSTypeNative.OBJECT_TYPE));
        }
      }
    }
  }
  public void setLastGeneration(boolean lastGeneration) {
    this.lastGeneration = lastGeneration;
  }
  public void setResolveMode(ResolveMode mode) {
    this.resolveMode = mode;
  }
  public void setTemplateTypeNames(List<String> names) {
    Preconditions.checkNotNull(names);
    for (String name : names) {
      templateTypes.put(name, new TemplateType(this, name));
    }
  }
  public void unregisterPropertyOnType(String propertyName, JSType type) {
    Map<String, ObjectType> typeSet = eachRefTypeIndexedByProperty.get(propertyName);
    if(typeSet != null) {
      typeSet.remove(type.toObjectType().getReferenceName());
    }
  }
  public static enum ResolveMode {
    LAZY_EXPRESSIONS(),

    LAZY_NAMES(),

    IMMEDIATE(),

  ;
  private ResolveMode() {
  }
  }
}