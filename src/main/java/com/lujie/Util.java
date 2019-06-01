package com.lujie;

import com.ibm.wala.classLoader.IBytecodeMethod;
import com.ibm.wala.classLoader.IClass;
import com.ibm.wala.classLoader.IMethod;
import com.ibm.wala.shrikeCT.InvalidClassFileException;
import com.ibm.wala.ssa.IR;
import com.ibm.wala.ssa.SSAAbstractInvokeInstruction;
import com.ibm.wala.ssa.SSAInstruction;
import com.ibm.wala.ssa.SSAThrowInstruction;
import com.ibm.wala.types.ClassLoaderReference;
import com.ibm.wala.types.MethodReference;
import com.ibm.wala.types.TypeName;

public class Util {
	public static boolean isApplicationClass(IClass iclass) {
		if (iclass == null) {
			return false;
		}
		return iclass.getClassLoader().getReference().equals(ClassLoaderReference.Application);
	}

	public static boolean isApplicationMethod(MethodReference method) {
		if (method == null) {
			return false;
		}
		return method.getDeclaringClass().getClassLoader().equals(ClassLoaderReference.Application);
	}

	public static int getLineNumber(IR ir, SSAInstruction inst) {
		IBytecodeMethod method = (IBytecodeMethod) ir.getMethod();
		int bytecodeIndex = -1;
		try {
			bytecodeIndex = method.getBytecodeIndex(inst.iindex);
		} catch (InvalidClassFileException e) {
			e.printStackTrace();
			System.exit(1);
		}
		return method.getLineNumber(bytecodeIndex);
	}

	public static void exitWithErrorMessage(String message) {
		System.err.println(message);
		System.exit(1);
	}

	public static boolean isExitInstruction(SSAInstruction ssaInstruction) {
		if (ssaInstruction instanceof SSAAbstractInvokeInstruction) {
			SSAAbstractInvokeInstruction ssaAbstractInvokeInstruction = ((SSAAbstractInvokeInstruction) ssaInstruction);
			if (ssaAbstractInvokeInstruction.getDeclaredTarget().getDeclaringClass().getName()
					.equals(TypeName.string2TypeName("Ljava/lang/System"))
					&& ssaAbstractInvokeInstruction.getDeclaredTarget().getName().toString().equals("exit")) {
				return true;
			}
		}
		if (ssaInstruction instanceof SSAThrowInstruction) {
			return true;
		}
		return false;
	}

	public static String getSimpleMethodToString(IMethod node) {
		StringBuilder sb = new StringBuilder();
		sb.append(node.getReference().getDeclaringClass().getName().getClassName().toString());
		sb.append("#");
		sb.append(node.getName().toString());
		return sb.toString();
	}

	public static boolean willThroughNPEFoo() {
		// list.indexOf
		// map.get
		// quote
		return false;
	}
}
