package com.lujie;

import java.util.Collection;
import java.util.Collections;
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
import com.ibm.wala.ssa.ISSABasicBlock;
import com.ibm.wala.ssa.SSAAbstractInvokeInstruction;
import com.ibm.wala.ssa.SSAArrayStoreInstruction;
import com.ibm.wala.ssa.SSACheckCastInstruction;
import com.ibm.wala.ssa.SSAConditionalBranchInstruction;
import com.ibm.wala.ssa.SSAInstruction;
import com.ibm.wala.ssa.SSAInvokeInstruction;
import com.ibm.wala.ssa.SSAPhiInstruction;
import com.ibm.wala.ssa.SSAPutInstruction;
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
	Set<MethodReference> returnNullMethodsref = HashSetFactory.make();
	private Set<MethodReference> resourceCloseMethod = HashSetFactory.make();
	Set<IMethod> transReturnNullMethods = HashSetFactory.make();
	private Map<MethodReference, Set<MethodReference>> caller2calleeRef = HashMapFactory.make();
	private Map<MethodReference, Set<MethodReference>> callee2callerRef = HashMapFactory.make();
	private Map<IMethod, Set<IMethod>> caller2callees = HashMapFactory.make();
	private Map<IMethod, Set<IMethod>> callee2callers = HashMapFactory.make();
	private Map<IMethod, Set<IMethod>> callee2check = HashMapFactory.make();
	private Map<IMethod, Set<CallerWLN>> callee2uncheck = HashMapFactory.make();
	private Map<MethodReference, IMethod> ref2method = HashMapFactory.make();

	public NPECallGraph(IClassHierarchy cha, boolean applicationOnly) {
		this.cha = cha;
		this.cache = new AnalysisCacheImpl();
	}

	public void init() {
		visistAllMethods();
		buildCheckGraph();
		filterChecker();
	}

	private void filterChecker() {
		for (Entry<IMethod, Set<IMethod>> checkEntry : callee2check.entrySet()) {
			Set<CallerWLN> uncheckCallers = callee2uncheck.get(checkEntry.getKey());
			if (uncheckCallers == null) {
				continue;
			}
			Set<CallerWLN> removedCallers = HashSetFactory.make();
			Set<IMethod> checkedCallers = HashSetFactory.make();
			checkedCallers.addAll(checkEntry.getValue());
			for (IMethod checkedCaller : checkEntry.getValue()) {
				checkedCallers.addAll(getPreNodes(checkedCaller, 1, 1));

			}
			for (CallerWLN uncheckCaller : uncheckCallers) {
				if (checkedCallers.contains(uncheckCaller.method)) {
					removedCallers.add(uncheckCaller);
				} else {
					/*
					 * Context insensitive, so False Negatives
					 */
					Set<IMethod> callerOfuncheckCaller = getPreNodes(uncheckCaller.method, 1, 5);
					callerOfuncheckCaller.retainAll(checkedCallers);
					if (!callerOfuncheckCaller.isEmpty()) {
						removedCallers.add(uncheckCaller);
					}
				}
			}
			uncheckCallers.removeAll(removedCallers);
		}
	}

	public Set<IMethod> getPreNodes(IMethod callee, int depth, int maxDepth) {
		Set<IMethod> callers = getPredNodes(callee);
		Set<IMethod> ret = HashSetFactory.make();
		if (depth > maxDepth || callers == null || callers.isEmpty()) {
			return ret;
		}
		ret.addAll(callers);
		for (IMethod caller : callers) {
			ret.addAll(getPreNodes(caller, depth + 1, maxDepth));
		}
		return ret;
	}

	private void buildCheckGraph() {
		for (IMethod returnNullNode : returnNullMethods) {
			findCaller(returnNullNode, returnNullNode, new HashSet<IMethod>());
		}
	}

	private void visistAllMethods() {
		for (IClass iclass : cha) {
			if (iclass.toString().contains("shaded")) {
				/* shaded or test jar should not be include */
				continue;
			}
			if (!Util.isApplicationClass(iclass)) {
				continue;
			}
			for (IMethod method : iclass.getDeclaredMethods()) {
				ref2method.put(method.getReference(), method);
				visistAllInstructions(method);
			}
		}
		returnNullMethodsref.removeAll(resourceCloseMethod);
		for (MethodReference returnNullMethodRef : returnNullMethodsref) {
			IMethod returnNullMethod = ref2method.get(returnNullMethodRef);
			if (returnNullMethod != null) {
				returnNullMethods.add(returnNullMethod);
			}
		}
		returnNullMethods.removeAll(transReturnNullMethods);
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
			if (ins instanceof SSAConditionalBranchInstruction) {
				int nullVal = isNEChecker(ir, (SSAConditionalBranchInstruction) ins);
				if (nullVal != -1) {
					SSAInstruction defIns = cache.getDefUse(ir).getDef(nullVal);
					if (defIns instanceof SSAInvokeInstruction) {
						MethodReference target = ((SSAInvokeInstruction) defIns).getDeclaredTarget();
						if (Util.isApplicationMethod(target)) {
							// may add the transReturnNullMethod, so we need remove them
							// in visistAllMethods
							if(ir.getBasicBlockForInstruction(ins).isCatchBlock()){
								resourceCloseMethod.add(target);
							}
							returnNullMethodsref.add(target);
						}
					}
				}
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

	public Set<IMethod> getPredNodes(IMethod method) {
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
		return callers;
	}

	private void findCaller(IMethod rootCallee, IMethod transcallee, Collection<IMethod> checkedNode) {
		if (checkedNode.contains(transcallee)) {
			return;
		}
		checkedNode.add(transcallee);
		Iterator<IMethod> callerIterator = getPredNodes(transcallee).iterator();
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
							SSAInstruction useIns = useIterator.next();
							if (useIns instanceof SSACheckCastInstruction) {
								def = useIns.getDef();
							}
						}
						useIterator = du.getUses(def);
						while (useIterator.hasNext()) {
							SSAInstruction useInstruction = useIterator.next();
							if (useInstruction instanceof SSAReturnInstruction) {
								transReturnNullMethods.add(caller);
								findCaller(rootCallee, caller, checkedNode);
							} else if (useInstruction instanceof SSAConditionalBranchInstruction) {
								/*
								 * false negatives, now we try to return false once we have a checker assume the
								 * code: if (RV!=null) RV.xxx(); RV.YYY. RV,YYY is not controlled by checker and
								 * cause NPE. SO TODO sound this implements.
								 */
								if (isNEChecker(ir, (SSAConditionalBranchInstruction) useInstruction, def)) {
									MapUtil.findOrCreateSet(callee2check, rootCallee).add(caller);
								}
							} else if (useInstruction instanceof SSAAbstractInvokeInstruction && !isUsedInInvoke(ir,
									(SSAAbstractInvokeInstruction) useInstruction, def, new HashSet<IMethod>())) {
								continue;
							} else if (useInstruction instanceof SSAPhiInstruction
									&& !phiIsUsed(ir, (SSAPhiInstruction) useInstruction)) {
								continue;
							} else if (useInstruction instanceof SSAPutInstruction) {
								/*
								 * false negatives,now we does't confider the field
								 */
								continue;
							} else if (useInstruction instanceof SSAArrayStoreInstruction) {
								/*
								 * LOG.info("{}{}",RNM,RNM);
								 */
								continue;
							} else {
								CallerWLN callerWLN = new CallerWLN();
								callerWLN.linenumber = Util.getLineNumber(ir, instruction);
								callerWLN.method = caller;
								MapUtil.findOrCreateSet(callee2uncheck, rootCallee).add(callerWLN);
							}
						}
					}
				}
			}
		}
	}

	private boolean phiIsUsed(IR ir, SSAPhiInstruction phiInstruction) {
		int def = phiInstruction.getDef();
		Iterator<SSAInstruction> useIterator = cache.getDefUse(ir).getUses(def);
		if (useIterator.hasNext()) {
			SSAInstruction useInstruction = useIterator.next();
			if (useInstruction instanceof SSAReturnInstruction) {
				return false;
			} else if (useInstruction instanceof SSAConditionalBranchInstruction
					&& isNEChecker(ir, (SSAConditionalBranchInstruction) useInstruction, def)) {
				return false;
			} else if (useInstruction instanceof SSAAbstractInvokeInstruction && !isUsedInInvoke(ir,
					(SSAAbstractInvokeInstruction) useInstruction, def, new HashSet<IMethod>())) {
				return false;
			} else if (useInstruction instanceof SSAPhiInstruction) {
				/*
				 * false negatives, we only consider one layer phi TODO implement more sound
				 */
				return false;
			}
		}
		return true;
	}

	private boolean isUsedInInvoke(IR ir, SSAAbstractInvokeInstruction ins, int target, HashSet<IMethod> visitedNodes) {
		if (ins.getDeclaredTarget().isInit() || ins.getDeclaredTarget().getName().toString().equals("append")) {
			return false;
		}
		if (!ins.isStatic() && ins.getUse(0) == target) {
			return true;
		}
		IMethod callee = ref2method.get(ins.getDeclaredTarget());
		if (callee == null) {
			return false;
		}
		for (int i = 0; i < ins.getNumberOfUses(); i++) {
			if (ins.getUse(i) == target) {
				return isUsedInCallee(cache.getIR(callee), i + 1, visitedNodes);
			}
		}
		return true;
	}

	private boolean isUsedInCallee(IR ir, int target, HashSet<IMethod> visitedNodes) {
		if (ir == null) {
			return false;
		}
		if (visitedNodes.contains(ir.getMethod())) {
			return false;
		}
		visitedNodes.add(ir.getMethod());
		Iterator<SSAInstruction> useInsIter = cache.getDefUse(ir).getUses(target);
		/*
		 * false positive, now we try to return false once a checker or invoke is false
		 * assume we use the RV many times and one of them has no checker and one has
		 * checker we must miss one SO TODO sound this implements.
		 */
		while (useInsIter.hasNext()) {
			SSAInstruction useInstruction = useInsIter.next();
			if (useInstruction instanceof SSAConditionalBranchInstruction
					&& isNEChecker(ir, (SSAConditionalBranchInstruction) useInstruction, target)) {
				return false;
			} else if (useInstruction instanceof SSAAbstractInvokeInstruction
					&& !isUsedInInvoke(ir, (SSAAbstractInvokeInstruction) useInstruction, target, visitedNodes)) {
				return false;
			} else if (useInstruction instanceof SSAPhiInstruction
					&& !phiIsUsed(ir, (SSAPhiInstruction) useInstruction)) {
				return false;
			} else if (useInstruction instanceof SSAPutInstruction) {
				/*
				 * false negatives,now we does't confider the field
				 */
				return false;
			} else if (useInstruction instanceof SSAArrayStoreInstruction) {
				/*
				 * LOG.info("{}{}",RNM,RNM);
				 */
				return false;
			}
		}
		return true;
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

	public int isNEChecker(IR ir, SSAConditionalBranchInstruction ssaInstruction) {
		if (ssaInstruction.getNumberOfUses() != 2) {
			return -1;
		}
		int use0 = ssaInstruction.getUse(0);
		int use1 = ssaInstruction.getUse(1);
		if (ir.getSymbolTable().isNullConstant(use0)) {
			return use1;
		}
		if (ir.getSymbolTable().isNullConstant(use1)) {
			return use0;
		}
		return -1;
	}

	public int getUncheckSize(IMethod method) {
		Set<CallerWLN> uncheckmethod = callee2uncheck.get(method);
		return uncheckmethod == null ? 0 : uncheckmethod.size();
	}

	public int getCheckSize(IMethod method) {
		Set<IMethod> checkmethod = callee2check.get(method);
		return checkmethod == null ? 0 : checkmethod.size();
	}

	public Set<IMethod> getReturnNullMethods() {
		return returnNullMethods;
	}

	public Set<CallerWLN> getUncheckCallers(IMethod method) {
		Set<CallerWLN> uncheckmethod = callee2uncheck.get(method);
		if (uncheckmethod == null) {
			return Collections.emptySet();
		}
		return callee2uncheck.get(method);
	}
}
