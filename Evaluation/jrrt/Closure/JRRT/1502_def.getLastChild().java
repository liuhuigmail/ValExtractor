package com.google.javascript.jscomp;
import com.google.common.base.Preconditions;
import com.google.common.base.Predicate;
import com.google.common.base.Predicates;
import com.google.common.collect.Lists;
import com.google.common.collect.Sets;
import com.google.javascript.jscomp.ControlFlowGraph.AbstractCfgNodeTraversalCallback;
import com.google.javascript.jscomp.ControlFlowGraph.Branch;
import com.google.javascript.jscomp.DataFlowAnalysis.FlowState;
import com.google.javascript.jscomp.MustBeReachingVariableDef.Definition;
import com.google.javascript.jscomp.MustBeReachingVariableDef.MustDef;
import com.google.javascript.jscomp.NodeTraversal.AbstractPostOrderCallback;
import com.google.javascript.jscomp.NodeTraversal.AbstractShallowCallback;
import com.google.javascript.jscomp.NodeTraversal.ScopedCallback;
import com.google.javascript.jscomp.Scope.Var;
import com.google.javascript.jscomp.graph.DiGraph.DiGraphEdge;
import com.google.javascript.jscomp.graph.DiGraph.DiGraphNode;
import com.google.javascript.rhino.Node;
import com.google.javascript.rhino.Token;
import java.util.Collection;
import java.util.List;
import java.util.Set;

class FlowSensitiveInlineVariables extends AbstractPostOrderCallback implements CompilerPass, ScopedCallback  {
  final private AbstractCompiler compiler;
  final private Set<Var> inlinedNewDependencies = Sets.newHashSet();
  private ControlFlowGraph<Node> cfg;
  private List<Candidate> candidates;
  private MustBeReachingVariableDef reachingDef;
  private MaybeReachingVariableUse reachingUses;
  final private static Predicate<Node> SIDE_EFFECT_PREDICATE = new Predicate<Node>() {
      @Override() public boolean apply(Node n) {
        if(n == null) {
          return false;
        }
        if(n.isCall() && NodeUtil.functionCallHasSideEffects(n)) {
          return true;
        }
        if(n.isNew() && NodeUtil.constructorCallHasSideEffects(n)) {
          return true;
        }
        if(n.isDelProp()) {
          return true;
        }
        for(com.google.javascript.rhino.Node c = n.getFirstChild(); c != null; c = c.getNext()) {
          if(!ControlFlowGraph.isEnteringNewCfgNode(c) && apply(c)) {
            return true;
          }
        }
        return false;
      }
  };
  public FlowSensitiveInlineVariables(AbstractCompiler compiler) {
    super();
    this.compiler = compiler;
  }
  private static boolean checkLeftOf(Node n, Node expressionRoot, Predicate<Node> predicate) {
    for(com.google.javascript.rhino.Node p = n.getParent(); p != expressionRoot; p = p.getParent()) {
      for(com.google.javascript.rhino.Node cur = p.getParent().getFirstChild(); cur != p; cur = cur.getNext()) {
        if(predicate.apply(cur)) {
          return true;
        }
      }
    }
    return false;
  }
  private static boolean checkRightOf(Node n, Node expressionRoot, Predicate<Node> predicate) {
    for(com.google.javascript.rhino.Node p = n; p != expressionRoot; p = p.getParent()) {
      for(com.google.javascript.rhino.Node cur = p.getNext(); cur != null; cur = cur.getNext()) {
        if(predicate.apply(cur)) {
          return true;
        }
      }
    }
    return false;
  }
  @Override() public void enterScope(NodeTraversal t) {
    if(t.inGlobalScope()) {
      return ;
    }
    if(LiveVariablesAnalysis.MAX_VARIABLES_TO_ANALYZE < t.getScope().getVarCount()) {
      return ;
    }
    ControlFlowAnalysis cfa = new ControlFlowAnalysis(compiler, false, true);
    Preconditions.checkState(t.getScopeRoot().isFunction());
    cfa.process(null, t.getScopeRoot().getLastChild());
    cfg = cfa.getCfg();
    reachingDef = new MustBeReachingVariableDef(cfg, t.getScope(), compiler);
    reachingDef.analyze();
    candidates = Lists.newLinkedList();
    new NodeTraversal(compiler, new GatherCandiates()).traverse(t.getScopeRoot().getLastChild());
    reachingUses = new MaybeReachingVariableUse(cfg, t.getScope(), compiler);
    reachingUses.analyze();
    for (Candidate c : candidates) {
      if(c.canInline(t.getScope())) {
        c.inlineVariable();
        if(!c.defMetadata.depends.isEmpty()) {
          inlinedNewDependencies.add(t.getScope().getVar(c.varName));
        }
      }
    }
  }
  @Override() public void exitScope(NodeTraversal t) {
  }
  @Override() public void process(Node externs, Node root) {
    (new NodeTraversal(compiler, this)).traverseRoots(externs, root);
  }
  @Override() public void visit(NodeTraversal t, Node n, Node parent) {
  }
  
  private class Candidate  {
    final private String varName;
    private Node def;
    final private Definition defMetadata;
    final private Node use;
    final private Node useCfgNode;
    private int numUseWithinUseCfgNode;
    Candidate(String varName, Definition defMetadata, Node use, Node useCfgNode) {
      super();
      Preconditions.checkArgument(use.isName());
      this.varName = varName;
      this.defMetadata = defMetadata;
      this.use = use;
      this.useCfgNode = useCfgNode;
    }
    private Node getDefCfgNode() {
      return defMetadata.node;
    }
    private boolean canInline(final Scope scope) {
      if(getDefCfgNode().isFunction()) {
        return false;
      }
      for (Var dependency : defMetadata.depends) {
        if(inlinedNewDependencies.contains(dependency)) {
          return false;
        }
      }
      getDefinition(getDefCfgNode(), null);
      getNumUseInUseCfgNode(useCfgNode, null);
      if(def == null) {
        return false;
      }
      if(def.isAssign() && !NodeUtil.isExprAssign(def.getParent())) {
        return false;
      }
      if(checkRightOf(def, getDefCfgNode(), SIDE_EFFECT_PREDICATE)) {
        return false;
      }
      if(checkLeftOf(use, useCfgNode, SIDE_EFFECT_PREDICATE)) {
        return false;
      }
      Node var_1502 = def.getLastChild();
      if(NodeUtil.mayHaveSideEffects(var_1502, compiler)) {
        return false;
      }
      if(numUseWithinUseCfgNode != 1) {
        return false;
      }
      if(NodeUtil.isWithinLoop(use)) {
        return false;
      }
      Collection<Node> uses = reachingUses.getUses(varName, getDefCfgNode());
      if(uses.size() != 1) {
        return false;
      }
      if(NodeUtil.has(def.getLastChild(), new Predicate<Node>() {
          @Override() public boolean apply(Node input) {
            switch (input.getType()){
              case Token.GETELEM:
              case Token.GETPROP:
              case Token.ARRAYLIT:
              case Token.OBJECTLIT:
              case Token.REGEXP:
              case Token.NEW:
              return true;
              case Token.NAME:
              Var var = scope.getOwnSlot(input.getString());
              if(var != null && var.getParentNode().isCatch()) {
                return true;
              }
            }
            return false;
          }
      }, new Predicate<Node>() {
          @Override() public boolean apply(Node input) {
            return !input.isFunction();
          }
      })) {
        return false;
      }
      if(NodeUtil.isStatementBlock(getDefCfgNode().getParent()) && getDefCfgNode().getNext() != useCfgNode) {
        CheckPathsBetweenNodes<Node, ControlFlowGraph.Branch> pathCheck = new CheckPathsBetweenNodes<Node, ControlFlowGraph.Branch>(cfg, cfg.getDirectedGraphNode(getDefCfgNode()), cfg.getDirectedGraphNode(useCfgNode), SIDE_EFFECT_PREDICATE, Predicates.<DiGraphEdge<Node, ControlFlowGraph.Branch>>alwaysTrue(), false);
        if(pathCheck.somePathsSatisfyPredicate()) {
          return false;
        }
      }
      return true;
    }
    private void getDefinition(Node n, Node parent) {
      AbstractCfgNodeTraversalCallback gatherCb = new AbstractCfgNodeTraversalCallback() {
          @Override() public void visit(NodeTraversal t, Node n, Node parent) {
            switch (n.getType()){
              case Token.NAME:
              if(n.getString().equals(varName) && n.hasChildren()) {
                def = n;
              }
              return ;
              case Token.ASSIGN:
              Node lhs = n.getFirstChild();
              if(lhs.isName() && lhs.getString().equals(varName)) {
                def = n;
              }
              return ;
            }
          }
      };
      NodeTraversal.traverse(compiler, n, gatherCb);
    }
    private void getNumUseInUseCfgNode(Node n, Node parant) {
      AbstractCfgNodeTraversalCallback gatherCb = new AbstractCfgNodeTraversalCallback() {
          @Override() public void visit(NodeTraversal t, Node n, Node parent) {
            if(n.isName() && n.getString().equals(varName) && !(parent.isAssign() && (parent.getFirstChild() == n))) {
              numUseWithinUseCfgNode++;
            }
          }
      };
      NodeTraversal.traverse(compiler, n, gatherCb);
    }
    private void inlineVariable() {
      Node defParent = def.getParent();
      Node useParent = use.getParent();
      if(def.isAssign()) {
        Node rhs = def.getLastChild();
        rhs.detachFromParent();
        Preconditions.checkState(defParent.isExprResult());
        while(defParent.getParent().isLabel()){
          defParent = defParent.getParent();
        }
        defParent.detachFromParent();
        useParent.replaceChild(use, rhs);
      }
      else 
        if(defParent.isVar()) {
          Node rhs = def.getLastChild();
          def.removeChild(rhs);
          useParent.replaceChild(use, rhs);
        }
        else {
          Preconditions.checkState(false, "No other definitions can be inlined.");
        }
      compiler.reportCodeChange();
    }
  }
  
  private class GatherCandiates extends AbstractShallowCallback  {
    @Override() public void visit(NodeTraversal t, Node n, Node parent) {
      DiGraphNode<Node, Branch> graphNode = cfg.getDirectedGraphNode(n);
      if(graphNode == null) {
        return ;
      }
      FlowState<MustDef> state = graphNode.getAnnotation();
      final MustDef defs = state.getIn();
      final Node cfgNode = n;
      AbstractCfgNodeTraversalCallback gatherCb = new AbstractCfgNodeTraversalCallback() {
          @Override() public void visit(NodeTraversal t, Node n, Node parent) {
            if(n.isName()) {
              if(parent == null) {
                return ;
              }
              if((NodeUtil.isAssignmentOp(parent) && parent.getFirstChild() == n) || parent.isVar() || parent.isInc() || parent.isDec() || parent.isParamList() || parent.isCatch()) {
                return ;
              }
              String name = n.getString();
              if(compiler.getCodingConvention().isExported(name)) {
                return ;
              }
              Definition def = reachingDef.getDef(name, cfgNode);
              if(def != null && !reachingDef.dependsOnOuterScopeVars(def)) {
                candidates.add(new Candidate(name, def, n, cfgNode));
              }
            }
          }
      };
      NodeTraversal.traverse(compiler, cfgNode, gatherCb);
    }
  }
}