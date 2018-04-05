package com.lujie;

import java.util.Map;
import java.util.Set;

import com.ibm.wala.ipa.callgraph.CGNode;
import com.ibm.wala.ipa.callgraph.CallGraph;
import com.ibm.wala.ssa.IR;
import com.ibm.wala.ssa.SSAConditionalBranchInstruction;
import com.ibm.wala.ssa.SSAInstruction;
import com.ibm.wala.util.collections.HashMapFactory;

public abstract class ControldependencyAnalysis {
	protected CallGraph callGraph;
	protected Map<CGNode, Integer> checkedCalleeCount;
	protected Map<CGNode, CGNode> trasnCalleeToRootCallee;
	public ControldependencyAnalysis(CallGraph callGraph, Map<CGNode, CGNode>trasnCalleeToRootCallee) {
		this.checkedCalleeCount = HashMapFactory.make();
		this.callGraph = callGraph;
		this.trasnCalleeToRootCallee = trasnCalleeToRootCallee;
	}

	public abstract Map<CGNode, Set<CGNode>> analysis(Map<CGNode, Set<CGNode>> calleeMap2Callers);

	public boolean isNEChecker(IR ir, SSAInstruction ssaInstruction, int def) {
		if (ssaInstruction instanceof SSAConditionalBranchInstruction) {
			if (ssaInstruction.getNumberOfUses() != 2) {
				return false;
			}
			int use0 = ssaInstruction.getUse(0);
			int use1 = ssaInstruction.getUse(1);
			if (ir.getSymbolTable().isNullConstant(use0) && use1 == def) {
				return true;
			}
			if (ir.getSymbolTable().isNullConstant(use1) && use0 == def) {
				return true;
			}
		}
		return false;
	}

	public Map<CGNode, Integer> getCheckedCalleeCount() {
		return checkedCalleeCount;
	}
	
}
