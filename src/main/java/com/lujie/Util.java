package com.lujie;

import com.ibm.wala.classLoader.IClass;
import com.ibm.wala.ipa.callgraph.CGNode;
import com.ibm.wala.ssa.SSAAbstractInvokeInstruction;
import com.ibm.wala.ssa.SSAInstruction;
import com.ibm.wala.ssa.SSAThrowInstruction;
import com.ibm.wala.types.ClassLoaderReference;
import com.ibm.wala.types.TypeName;

public class Util {
	public static boolean isApplicationNode(CGNode node) {
		IClass cls = node.getClassHierarchy().lookupClass(
				node.getMethod().getReference().getDeclaringClass());
		if (cls == null) {
			return false;
		}
		return cls.getClassLoader().getReference()
				.equals(ClassLoaderReference.Application);
	}

	public static void exitWithErrorMessage(String message) {
		System.err.println(message);
		System.exit(1);
	}

	public static boolean isExitInstruction(SSAInstruction ssaInstruction) {
		if (ssaInstruction instanceof SSAAbstractInvokeInstruction) {
			SSAAbstractInvokeInstruction ssaAbstractInvokeInstruction = ((SSAAbstractInvokeInstruction) ssaInstruction);
			if (ssaAbstractInvokeInstruction.getDeclaredTarget()
					.getDeclaringClass().getName()
					.equals(TypeName.string2TypeName("Ljava/lang/System"))
					&& ssaAbstractInvokeInstruction.getDeclaredTarget()
							.getName().toString().equals("exit")) {
				return true;
			}
		}
		if (ssaInstruction instanceof SSAThrowInstruction){
			return true;
		}
		return false;
	}
	
	public static String getSimpleMethodToString(CGNode node){
		StringBuilder sb = new StringBuilder();
		sb.append(node.getMethod().getReference().getDeclaringClass().getName().getClassName().toString());
		sb.append("#");
		sb.append(node.getMethod().getName().toString());
		return sb.toString();
	}
	
	public static boolean willThroughNPEFoo(){
		//list.indexOf
		//map.get
		//quote
		return false;
	}
}
