package com.lujie;

import com.ibm.wala.classLoader.IClass;
import com.ibm.wala.ipa.callgraph.CGNode;
import com.ibm.wala.types.ClassLoaderReference;

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
	
	public static void exitWithErrorMessage(String message){
		System.err.println(message);
		System.exit(1);
	}
}
