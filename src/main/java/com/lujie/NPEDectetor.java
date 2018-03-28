package com.lujie;

import java.io.File;
import java.io.IOException;
import java.util.Collection;
import java.util.Iterator;
import java.util.Properties;

import com.ibm.wala.ipa.callgraph.AnalysisCacheImpl;
import com.ibm.wala.ipa.callgraph.AnalysisOptions;
import com.ibm.wala.ipa.callgraph.AnalysisScope;
import com.ibm.wala.ipa.callgraph.CallGraph;
import com.ibm.wala.ipa.callgraph.CallGraphBuilder;
import com.ibm.wala.ipa.callgraph.CallGraphBuilderCancelException;
import com.ibm.wala.ipa.callgraph.CallGraphStats;
import com.ibm.wala.ipa.callgraph.Entrypoint;
import com.ibm.wala.ipa.callgraph.impl.AllApplicationEntrypoints;
import com.ibm.wala.ipa.callgraph.impl.Util;
import com.ibm.wala.ipa.cha.ClassHierarchy;
import com.ibm.wala.ipa.cha.ClassHierarchyException;
import com.ibm.wala.ipa.cha.ClassHierarchyFactory;
import com.ibm.wala.properties.WalaProperties;
import com.ibm.wala.util.WalaException;
import com.ibm.wala.util.collections.HashSetFactory;
import com.ibm.wala.util.config.AnalysisScopeReader;
import com.ibm.wala.util.io.FileProvider;
import com.ibm.wala.util.io.FileUtil;

/**
 * 
 * */
public class NPEDectetor {
	private String jarDir = null;
	private CallGraph callGraph = null;
	private boolean mainEntrypoints = false;
	private ClassHierarchy cha = null;
	private static long CUTOFF_SIZE = 1000;

	public static void main(String[] args) throws IOException,
			ClassHierarchyException, IllegalArgumentException,
			CallGraphBuilderCancelException {
		NPEDectetor dectetor = new NPEDectetor();
		dectetor.checkJreVersion();
		dectetor.checkParameter(args);
		dectetor.makeCallGraph();
	}

	private void checkParameter(String[] args) {
		if (args.length != 2) {
			System.out.println("wrong parameter number");
			System.exit(1);
		}
		jarDir = args[0];
		if (!(new File(jarDir).isDirectory())) {
			System.out.println("wrong tart jar directory");
			System.exit(1);
		}
	}

	private void makeCallGraph() throws IOException, ClassHierarchyException,
			IllegalArgumentException, CallGraphBuilderCancelException {
		long start_time = System.currentTimeMillis();
		System.out.println("start to make call graph");
		FileProvider fileProvider = new FileProvider();
		File exclusionsFile = fileProvider
				.getFile("Java60RegressionExclusions.txt");
		String jarFiles = findJarFiles(new String[] { jarDir });
		AnalysisScope scope = AnalysisScopeReader.makeJavaBinaryAnalysisScope(
				jarFiles, exclusionsFile);
		cha = ClassHierarchyFactory.make(scope);
		Iterable<Entrypoint> entryPointIterator = null;
		if (mainEntrypoints) {
			entryPointIterator = Util.makeMainEntrypoints(scope, cha);
		} else {
			entryPointIterator = new AllApplicationEntrypoints(scope, cha);
		}
		AnalysisOptions options = new AnalysisOptions(scope, entryPointIterator);
		// 0-CFA is faster and more precise
		CallGraphBuilder builder = Util.makeZeroCFABuilder(options,
				new AnalysisCacheImpl(), cha, scope);
		callGraph = builder.makeCallGraph(options, null);
		System.out.println(CallGraphStats.getStats(callGraph));
		System.out.println("Time spent ont building CHA and CG:"
				+ (System.currentTimeMillis() - start_time) + "ms");
	}

	private String findJarFiles(String[] directories) {
		long size = 0;
		Collection<String> result = HashSetFactory.make();
		for (int i = 0; i < directories.length; i++) {
			for (File f : FileUtil.listFiles(directories[i], ".*\\.jar", true)) {
				result.add(f.getAbsolutePath());
				size += f.length();
			}
		}
		if (size > CUTOFF_SIZE) {
			mainEntrypoints = true;
		}
		return composeString(result);
	}

	private String composeString(Collection<String> s) {
		StringBuffer result = new StringBuffer();
		Iterator<String> it = s.iterator();
		for (int i = 0; i < s.size() - 1; i++) {
			result.append(it.next());
			result.append(File.pathSeparator);
		}
		result.append(it.next());
		return result.toString();
	}

	private void checkJreVersion() {
		try {
			Properties p = WalaProperties.loadProperties();
			String javaHome = p.getProperty(WalaProperties.J2SE_DIR);
			if (!javaHome.contains("1.7")) {
				System.err
						.println("check your javahome wrong jdk version , must be 1.7");
			}
		} catch (WalaException e) {
			e.printStackTrace();
			System.exit(1);
		}
	}
}
