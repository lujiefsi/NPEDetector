package com.lujie;

import java.util.Collection;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;

import com.ibm.wala.classLoader.CallSiteReference;
import com.ibm.wala.classLoader.IClass;
import com.ibm.wala.classLoader.IMethod;
import com.ibm.wala.ipa.callgraph.AnalysisCacheImpl;
import com.ibm.wala.ipa.callgraph.CGNode;
import com.ibm.wala.ipa.callgraph.IAnalysisCacheView;
import com.ibm.wala.ipa.cha.IClassHierarchy;
import com.ibm.wala.ssa.DefUse;
import com.ibm.wala.ssa.IR;
import com.ibm.wala.ssa.SSAAbstractInvokeInstruction;
import com.ibm.wala.ssa.SSAConditionalBranchInstruction;
import com.ibm.wala.ssa.SSAInstruction;
import com.ibm.wala.ssa.SSAInvokeInstruction;
import com.ibm.wala.ssa.SSAPhiInstruction;
import com.ibm.wala.ssa.SSAReturnInstruction;
import com.ibm.wala.ssa.SymbolTable;
import com.ibm.wala.types.MethodReference;
import com.ibm.wala.util.collections.HashMapFactory;
import com.ibm.wala.util.collections.HashSetFactory;
import com.ibm.wala.util.collections.MapUtil;

public class NPECallGraph {
	private IClassHierarchy cha;
	private IAnalysisCacheView cache;
	Set<IMethod> returnNullMethods = HashSetFactory.make();
	private Map<MethodReference, Set<MethodReference>> caller2calleeRef = HashMapFactory.make();
	private Map<MethodReference, Set<MethodReference>> callee2callerRef = HashMapFactory.make();
	private Map<IMethod, Set<IMethod>> caller2callees = HashMapFactory.make();
	private Map<IMethod, Set<IMethod>> callee2callers = HashMapFactory.make();
	private Map<IMethod, Set<IMethod>> callee2check = HashMapFactory.make();
	private Map<IMethod, Set<IMethod>> callee2uncheck = HashMapFactory.make();
	private Map<MethodReference, IMethod> ref2method = HashMapFactory.make();

	public NPECallGraph(IClassHierarchy cha, boolean applicationOnly) {
		this.cha = cha;
		this.cache = new AnalysisCacheImpl();
	}

	public void init() {
		visistAllMethods();
		buildCheckGraph();
		buildUncheckGraph();
	}

	private void buildCheckGraph() {
		for (IMethod returnNullNode : returnNullMethods) {
			findCaller(returnNullNode, returnNullNode, new HashSet<IMethod>());
		}
	}

	private void buildUncheckGraph() {
		for (IMethod rnm : returnNullMethods) {
			Set<IMethod> uncheckCallers = MapUtil.findOrCreateSet(callee2uncheck, rnm);
			uncheckCallers.addAll(callee2callers.get(rnm));
			Set<IMethod> checkCallers = MapUtil.findOrCreateSet(callee2check, rnm);
			uncheckCallers.removeAll(checkCallers);
		}
	}

	private void visistAllMethods() {
		for (IClass iclass : cha) {
			if (!Util.isApplicationClass(iclass)) {
				continue;
			}
			for (IMethod method : iclass.getDeclaredMethods()) {
				ref2method.put(method.getReference(), method);
				visistAllInstructions(method);
			}
		}
	}

	private void visistAllInstructions(IMethod method) {
		IR ir = cache.getIR(method);
		if (ir == null || ir.getInstructions() == null) {
			return;
		}
		for (SSAInstruction ins : ir.getInstructions()) {
			if (ins instanceof SSAReturnInstruction && isReturnNullInstruction((SSAReturnInstruction) ins, ir)) {
				returnNullMethods.add(method);
			}
			if (ins instanceof SSAInvokeInstruction) {
				MethodReference target = ((SSAInvokeInstruction) ins).getDeclaredTarget();
				if (!Util.isApplicationMethod(target)) {
					continue;
				}
				MapUtil.findOrCreateSet(callee2callerRef, target).add(method.getReference());
				MapUtil.findOrCreateSet(caller2calleeRef, method.getReference()).add(target);
			}
		}
	}

	private boolean isReturnNullInstruction(SSAReturnInstruction returnIns, IR ir) {
		DefUse defUse = cache.getDefUse(ir);
		SymbolTable symbolTable = ir.getSymbolTable();
		int use = returnIns.getUse(0);
		if (use < 0) {
			return false;
		}
		// case 0: directly "return null"
		if (symbolTable.isNullConstant(use)) {
			return true;
		}
		// case 1: phi v0 = v1:#str, v2:#null,...
		// TODO handle that v1 is still def by SSAPhiInstruction
		SSAInstruction defIns = defUse.getDef(use);
		if (defIns instanceof SSAPhiInstruction) {
			for (int i = 0; i < defIns.getNumberOfUses(); i++) {
				use = defIns.getUse(0);
				if (use != -1 && symbolTable.isNullConstant(use)) {
					return true;
				}
			}
		}
		// case n:?, maybe
		return false;
	}

	public Iterator<CallSiteReference> getPossibleSites(CGNode caller, CGNode transcallee) {
		// TODO Auto-generated method stub
		return null;
	}

	public Iterator<IMethod> getPredNodes(IMethod method) {
		Set<MethodReference> callersRef = MapUtil.findOrCreateSet(callee2callerRef, method.getReference());
		Set<IMethod> callers = MapUtil.findOrCreateSet(callee2callers, method);
		if (!callersRef.isEmpty() && callers.isEmpty()) {
			for (MethodReference callerRef : callersRef) {
				IMethod caller = ref2method.get(callerRef);
				if (caller != null) {
					callers.add(caller);
				}
			}
		}
		return callers.iterator();
	}

	private void findCaller(IMethod rootCallee, IMethod transcallee, Collection<IMethod> checkedNode) {
		if (checkedNode.contains(transcallee)) {
			return;
		}
		checkedNode.add(transcallee);
		Iterator<IMethod> callerIterator = getPredNodes(transcallee);
		while (callerIterator.hasNext()) {
			IMethod caller = callerIterator.next();
			IR ir = cache.getIR(caller);
			if (ir == null || ir.getInstructions() == null) {
				continue;
			}
			DefUse du = cache.getDefUse(ir);
			for (SSAInstruction instruction : ir.getInstructions()) {
				if (instruction instanceof SSAAbstractInvokeInstruction) {
					SSAAbstractInvokeInstruction ssaAbstractInvokeInstruction = (SSAAbstractInvokeInstruction) instruction;
					if (ssaAbstractInvokeInstruction.getDeclaredTarget().equals(transcallee.getReference())) {
						int def = ssaAbstractInvokeInstruction.getDef();
						Iterator<SSAInstruction> useIterator = du.getUses(def);
						if (useIterator.hasNext()) {
							SSAInstruction useInstruction = useIterator.next();
							if (useInstruction instanceof SSAReturnInstruction) {
								findCaller(rootCallee, caller, checkedNode);
							} else if (useInstruction instanceof SSAConditionalBranchInstruction) {
								if (isNEChecker(ir, (SSAConditionalBranchInstruction) useInstruction, def)) {
									MapUtil.findOrCreateSet(callee2check, rootCallee).add(caller);
								}
							}
						}
					}
				}

			}
		}
	}

	public boolean isNEChecker(IR ir, SSAConditionalBranchInstruction ssaInstruction, int def) {
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
		return false;
	}

	public int getUncheckSize(IMethod method) {
		return callee2uncheck.get(method).size();
	}
	public int getCheckSize(IMethod method) {
		return callee2check.get(method).size();
	}

	public  Set<IMethod> getReturnNullMethods() {
		return returnNullMethods;
	}

	public Set<IMethod> getUncheckCallers(IMethod method) {
		// TODO Auto-generated method stub
		return callee2uncheck.get(method);
	}
}
