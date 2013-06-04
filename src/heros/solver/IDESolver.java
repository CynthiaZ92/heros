/*******************************************************************************
 * Copyright (c) 2012 Eric Bodden.
 * Copyright (c) 2013 Tata Consultancy Services & Ecole Polytechnique de Montreal
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the GNU Lesser Public License v2.1
 * which accompanies this distribution, and is available at
 * http://www.gnu.org/licenses/old-licenses/gpl-2.0.html
 * 
 * Contributors:
 *     Eric Bodden - initial API and implementation
 *     Marc-Andre Laverdiere-Papineau - Fixed race condition
 ******************************************************************************/
package heros.solver;


import heros.DontSynchronize;
import heros.EdgeFunction;
import heros.EdgeFunctionCache;
import heros.EdgeFunctions;
import heros.FlowFunction;
import heros.FlowFunctionCache;
import heros.FlowFunctions;
import heros.IDETabulationProblem;
import heros.InterproceduralCFG;
import heros.JoinLattice;
import heros.SynchronizedBy;
import heros.ZeroedFlowFunctions;
import heros.edgefunc.EdgeIdentity;
import heros.incremental.UpdatableInterproceduralCFG;
import heros.incremental.UpdatableWrapper;
import heros.util.ConcurrentHashSet;
import heros.util.Utils;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import com.google.common.base.Predicate;
import com.google.common.cache.CacheBuilder;
import com.google.common.collect.HashBasedTable;
import com.google.common.collect.Maps;
import com.google.common.collect.Table;
import com.google.common.collect.Table.Cell;


/**
 * Solves the given {@link IDETabulationProblem} as described in the 1996 paper by Sagiv,
 * Horwitz and Reps. To solve the problem, call {@link #solve()}. Results can then be
 * queried by using {@link #resultAt(Object, Object)} and {@link #resultsAt(Object)}.
 * 
 * Note that this solver and its data structures internally use mostly {@link LinkedHashSet}s
 * instead of normal {@link HashSet}s to fix the iteration order as much as possible. This
 * is to produce, as much as possible, reproducible benchmarking results. We have found
 * that the iteration order can matter a lot in terms of speed.
 *
 * @param <N> The type of nodes in the interprocedural control-flow graph. 
 * @param <D> The type of data-flow facts to be computed by the tabulation problem.
 * @param <M> The type of objects used to represent methods.
 * @param <V> The type of values to be computed along flow edges.
 * @param <I> The type of inter-procedural control-flow graph being used.
 */
public class IDESolver<N,D,M,V,I extends InterproceduralCFG<N, M>> {
	
	/**
	 * Enumeration containing all possible modes in which this solver can work
	 */
	private enum OperationMode
	{
		/**
		 * A forward-only computation is performed and no data is deleted
		 */
		Compute,
		/**
		 * An incremental update is performed on the data, deleting outdated
		 * results when necessary
		 */
		Update
	};
	
	/**
	 * Modes that can be used to configure the solver's internal tradeoffs
	 */
	private enum OptimizationMode {
		/**
		 * Optimize the solver for performance. This may cost additional memory.
		 */
		Performance,
		/**
		 * Optimize the solver for minimal memory consumption. This may affect
		 * performance.
		 */
		Memory
	}

	public static CacheBuilder<Object, Object> DEFAULT_CACHE_BUILDER = CacheBuilder.newBuilder().concurrencyLevel(Runtime.getRuntime().availableProcessors()).initialCapacity(10000).softValues();
	
	public static final boolean DEBUG = !System.getProperty("HEROS_DEBUG", "false").equals("false");
	public static final boolean DEBUG_VERBOSE = System.getProperty("HEROS_DEBUG", "false").equals("verbose");
	
	//executor for dispatching individual compute jobs (may be multi-threaded)
	@DontSynchronize("only used by single thread")
	protected CountingThreadPoolExecutor executor;
	
	@DontSynchronize("only used by single thread")
	protected int numThreads;
	
	@SynchronizedBy("thread safe data structure, consistent locking when used")
	protected final JumpFunctions<N,D,V> jumpFn;
	@SynchronizedBy("thread safe data structure, consistent locking when used")
	protected Map<N,Set<D>> jumpSave = null;
	
	//stores summaries that were queried before they were computed
	//see CC 2010 paper by Naeem, Lhotak and Rodriguez
	@SynchronizedBy("consistent lock on 'incoming'")
	protected final Table<N,D,Table<N,D,EdgeFunction<V>>> endSummary = HashBasedTable.create();

	//edges going along calls
	//see CC 2010 paper by Naeem, Lhotak and Rodriguez
	@SynchronizedBy("consistent lock on field")
	protected final Table<N,D,Map<N,Set<D>>> incoming = HashBasedTable.create();
	
	@DontSynchronize("stateless")
	protected final FlowFunctions<N, D, M> flowFunctions;

	@DontSynchronize("stateless")
	protected final EdgeFunctions<N,D,M,V> edgeFunctions;

	@DontSynchronize("only used by single thread")
	protected final Set<N> initialSeeds;

	@DontSynchronize("stateless")
	protected final JoinLattice<V> valueLattice;
	
	@DontSynchronize("stateless")
	protected final EdgeFunction<V> allTop;

	@DontSynchronize("only used by single thread - phase II not parallelized (yet)")
	protected final Table<N,D,V> val = HashBasedTable.create();	
	
	@DontSynchronize("benign races")
	public long flowFunctionApplicationCount;

	@DontSynchronize("benign races")
	public long flowFunctionConstructionCount;
	
	@DontSynchronize("benign races")
	public long propagationCount;
	
	@DontSynchronize("benign races")
	public long durationFlowFunctionConstruction;
	
	@DontSynchronize("benign races")
	public long durationFlowFunctionApplication;

	@DontSynchronize("stateless")
	protected final IDETabulationProblem<N,D,M,V,I> tabulationProblem;
	
	@DontSynchronize("readOnly")
	protected final FlowFunctionCache<N,D,M> ffCache; 

	@DontSynchronize("readOnly")
	protected final EdgeFunctionCache<N,D,M,V> efCache;

	@DontSynchronize("readOnly")
	protected final boolean followReturnsPastSeeds;

	@DontSynchronize("readOnly")
	protected final boolean computeValues;
	
	@DontSynchronize("only used by single thread")
	private OperationMode operationMode = OperationMode.Compute;

	@DontSynchronize("only used by single thread")
	private OptimizationMode optimizationMode = OptimizationMode.Performance;

	@DontSynchronize("concurrent data structure")
	private Set<N> changedNodes = null;

	@DontSynchronize("only written by single thread")
	private Map<M, Set<N>> changeSet = null;
	
	/**
	 * Creates a solver for the given problem, which caches flow functions and edge functions.
	 * The solver must then be started by calling {@link #solve()}.
	 */
	public IDESolver(IDETabulationProblem<N,D,M,V,I> tabulationProblem) {
		this(tabulationProblem, DEFAULT_CACHE_BUILDER, DEFAULT_CACHE_BUILDER);
	}

	/**
	 * Creates a solver for the given problem, constructing caches with the given {@link CacheBuilder}. The solver must then be started by calling
	 * {@link #solve()}.
	 * @param flowFunctionCacheBuilder A valid {@link CacheBuilder} or <code>null</code> if no caching is to be used for flow functions.
	 * @param edgeFunctionCacheBuilder A valid {@link CacheBuilder} or <code>null</code> if no caching is to be used for edge functions.
	 */
	public IDESolver(IDETabulationProblem<N,D,M,V,I> tabulationProblem, @SuppressWarnings("rawtypes") CacheBuilder flowFunctionCacheBuilder, @SuppressWarnings("rawtypes") CacheBuilder edgeFunctionCacheBuilder) {
		if(DEBUG) {
			flowFunctionCacheBuilder = flowFunctionCacheBuilder.recordStats();
			edgeFunctionCacheBuilder = edgeFunctionCacheBuilder.recordStats();
		}
		this.tabulationProblem = tabulationProblem;
		FlowFunctions<N, D, M> flowFunctions = tabulationProblem.autoAddZero() ?
				new ZeroedFlowFunctions<N,D,M>(tabulationProblem.flowFunctions(), tabulationProblem.zeroValue()) : tabulationProblem.flowFunctions(); 
		EdgeFunctions<N, D, M, V> edgeFunctions = tabulationProblem.edgeFunctions();
		if(flowFunctionCacheBuilder!=null) {
			ffCache = new FlowFunctionCache<N,D,M>(flowFunctions, flowFunctionCacheBuilder);
			flowFunctions = ffCache;
		} else {
			ffCache = null;
		}
		if(edgeFunctionCacheBuilder!=null) {
			efCache = new EdgeFunctionCache<N,D,M,V>(edgeFunctions, edgeFunctionCacheBuilder);
			edgeFunctions = efCache;
		} else {
			efCache = null;
		}
		this.flowFunctions = flowFunctions;
		this.edgeFunctions = edgeFunctions;
		this.initialSeeds = tabulationProblem.initialSeeds();
		this.valueLattice = tabulationProblem.joinLattice();
		this.allTop = tabulationProblem.allTopFunction();
		this.jumpFn = new JumpFunctions<N,D,V>(allTop);
		this.followReturnsPastSeeds = tabulationProblem.followReturnsPastSeeds();
		this.numThreads = Math.max(1,tabulationProblem.numThreads());
		this.computeValues = tabulationProblem.computeValues();
		this.executor = getExecutor();
	}

	/**
	 * Convenience method for accessing the interprocedural control flow graph
	 * inside the tabulation problem
	 * @return The tabulation problem's icfg
	 */
	protected I icfg() {
		return this.tabulationProblem.interproceduralCFG();
	}
	
	/**
	 * Runs the solver on the configured problem. This can take some time.
	 */
	public void solve() {
		// Make sure to remove all leftovers from previous runs
		clearResults();

		// We are in forward-computation-only mode
		this.operationMode = OperationMode.Compute;
		
		  /*
		   * Forward-tabulates the same-level realizable paths and associated functions.
		   * Note that this is a little different from the original IFDS formulations because
		   * we can have statements that are, for instance, both "normal" and "exit" statements.
		   * This is for instance the case on a "throw" statement that may on the one hand
		   * lead to a catch block but on the other hand exit the method depending
		   * on the exception being thrown.
		   */
		D zeroValue = tabulationProblem.zeroValue();
		for(N startPoint: initialSeeds) {
			propagate(zeroValue, startPoint, zeroValue, allTop);
			scheduleEdgeProcessing(new PathEdge<N,D,M>(zeroValue, startPoint, zeroValue));
			jumpFn.addFunction(zeroValue, startPoint, zeroValue, EdgeIdentity.<V>v());
		}
		awaitCompletionComputeValuesAndShutdown(this.computeValues);
	}
	
	/**
	 * Incrementally updates the results of this solver
	 */
	@SuppressWarnings("unchecked")
	public void update(I newCFG) {
		// Check whether we need to update anything at all.
		if (newCFG == icfg())
			return;
		
		// Incremental updates must have been enabled before computing the
		// initial solver run.
		if (!(icfg() instanceof UpdatableInterproceduralCFG))
			throw new UnsupportedOperationException("Current CFG does not support incremental updates");
		if (!(newCFG instanceof UpdatableInterproceduralCFG))
			throw new UnsupportedOperationException("New CFG does not support incremental updates");
	
		// Update the control flow graph
		UpdatableInterproceduralCFG<N, M> oldcfg = (UpdatableInterproceduralCFG<N, M>) icfg();
		UpdatableInterproceduralCFG<N, M> newcfg = (UpdatableInterproceduralCFG<N, M>) newCFG;
		I newI = (I) newcfg;
		tabulationProblem.updateCFG(newI);
		
		long beforeChangeset = System.nanoTime();
		if (DEBUG)
			System.out.println("Computing changeset...");

		int edgeCount = this.optimizationMode == OptimizationMode.Performance ?
				jumpFn.getEdgeCount() : 5000;
		int nodeCount = this.optimizationMode == OptimizationMode.Performance ?
				jumpFn.getTargetCount() : 5000;
		
		// Next, we need to create a change set on the control flow graph
		Map<UpdatableWrapper<N>, List<UpdatableWrapper<N>>> expiredEdges =
				new HashMap<UpdatableWrapper<N>, List<UpdatableWrapper<N>>>(edgeCount);
		Map<UpdatableWrapper<N>, List<UpdatableWrapper<N>>> newEdges =
				new HashMap<UpdatableWrapper<N>, List<UpdatableWrapper<N>>>(edgeCount);
		Set<UpdatableWrapper<N>> newNodes = new HashSet<UpdatableWrapper<N>>(nodeCount);
		Set<UpdatableWrapper<N>> expiredNodes = new HashSet<UpdatableWrapper<N>>(nodeCount);
		oldcfg.computeCFGChangeset(newcfg, expiredEdges, newEdges, newNodes,
				expiredNodes);
		
		// Change the wrappers so that they point to the new Jimple objects
		long beforeMerge = System.nanoTime();
		newcfg.merge(oldcfg);
		System.out.println("CFG wrappers merged in " + (System.nanoTime() - beforeMerge) / 1E9
				+ " seconds.");
		
		// Invalidate all cached functions
		ffCache.invalidateAll();
		efCache.invalidateAll();

		System.out.println("Changeset computed in " + (System.nanoTime() - beforeChangeset) / 1E9
				+ " seconds. Found " + expiredEdges.size() + " expired edges, "
				+ newEdges.size() + " new edges, "
				+ expiredNodes.size() + " expired nodes, and "
				+ newNodes.size() + " new nodes.");

		// If we have not computed any graph changes, we are done
		if (expiredEdges.size() == 0 && newEdges.size() == 0) {
			System.out.println("CFG is unchanged, aborting update...");
			return;
		}

		// We need to keep track of the records we have already updated.
		// To avoid having to (costly) enlarge hash maps during the run, we
		// use the current size as an estimate.
		this.jumpSave = new HashMap<N, Set<D>>(this.jumpFn.getTargetCount());
		this.changedNodes = new ConcurrentHashSet<N>((int) this.propagationCount);
		this.propagationCount = 0;
		
		// Make sure we don't cache any expired nodes
		long beforeRemove = System.nanoTime();
		if (DEBUG)
			System.out.println("Removing " + expiredNodes.size() + " expired nodes...");
		for (UpdatableWrapper<N> n : expiredNodes) {
			// Expired nodes should not have been updated
			assert !n.hasPreviousContents();

			this.jumpFn.removeByTarget((N) n);
			Utils.removeElementFromTable(this.incoming, (N) n);
			Utils.removeElementFromTable(this.endSummary, (N) n);

			for (Cell<N, D, Map<N, Set<D>>> cell : incoming.cellSet())
				cell.getValue().remove(n);
			for (Cell<N, D, Table<N, D, EdgeFunction<V>>> cell : endSummary.cellSet())
				Utils.removeElementFromTable(cell.getValue(), (N) n);
		}
		if (DEBUG)
			System.out.println("Expired nodes removed in "
					+ (System.nanoTime() - beforeRemove) / 1E9
					+ " seconds.");

		// Process edge insertions. This will only do the incoming edges of new
		// nodes as the outgoing ones will only be available after the incoming
		// ones have been processed and the graph has been extended.
		this.operationMode = OperationMode.Update;
		changeSet = new HashMap<M, Set<N>>(newEdges.size() + expiredEdges.size());
		if (!newEdges.isEmpty())
			changeSet.putAll(updateEdges(newEdges, newNodes));
		
		// Process edge deletions
		if (!expiredEdges.isEmpty())
			changeSet.putAll(updateEdges(expiredEdges, expiredNodes));
		
		int expiredEdgeCount = expiredEdges.size();
		int newEdgeCount = newEdges.size();
		newNodes = null;
		expiredNodes = null;
		newEdges = null;
		expiredEdges = null;
		oldcfg = null;
//		Runtime.getRuntime().gc();		// save memory

		// Construct the worklist for all entries in the change set
		System.out.println("Processing worklist for edges...");
		int edgeIdx = 0;
		long beforeEdges = System.nanoTime();
		for (M m : changeSet.keySet())
			for (N preLoop : changeSet.get(m)) {
				assert newcfg.containsStmt((UpdatableWrapper<N>) preLoop);
				
				// If a predecessor in the same method has already been
				// the start point of a propagation, we can skip this one.
				if (this.predecessorRepropagated(changeSet.get(m), preLoop))
					continue;
				// If another propagation has already visited this node,
				// starting a new propagation from here cannot create
				// any fact changes.
				if (changedNodes.contains(preLoop))
					continue;
				
				executor = getExecutor();
				edgeIdx++;
				
				for (Cell<D, D, EdgeFunction<V>> srcEntry : jumpFn.lookupByTarget(preLoop)) {
					D srcD = srcEntry.getRowKey();
					D tgtD = srcEntry.getColumnKey();
	
					if (DEBUG)
						System.out.println("Reprocessing edge: <" + srcD
								+ "> -> <" + preLoop + ", " + tgtD + ">");
					scheduleEdgeProcessing(new PathEdge<N,D,M>(srcD, preLoop, tgtD));	
				}
			
				if (DEBUG)
					System.out.println("Processing worklist for method " + m + "...");
				this.operationMode = OperationMode.Update;
				this.jumpSave.clear();
				awaitCompletionComputeValuesAndShutdown(false);
			}
		
		System.out.println("Phase 1: Actually processed " + edgeIdx + " of "
				+ (newEdgeCount + expiredEdgeCount) + " changed edges in "
				+ (System.nanoTime() - beforeEdges) / 1E9 + " seconds");

		// Phase 2: Make sure that all incoming edges to join points are considered
		edgeIdx = 0;
		executor = getExecutor();
		
		long prePhase2 = System.nanoTime();
		operationMode = OperationMode.Compute;
		UpdatableInterproceduralCFG<N, M> icfg = (UpdatableInterproceduralCFG<N, M>) icfg();
		for (N n : this.changedNodes) {
			// If this is an exit node and we have an old end summary, we
			// need to delete it as well
			if (icfg().isExitStmt(n))
				for (N sP : icfg().getStartPointsOf(icfg().getMethodOf(n))) {
					 Map<D, Table<N, D, EdgeFunction<V>>> endRow = endSummary.row(sP);
					 for (D d1 : endRow.keySet()) {
						 Table<N, D, EdgeFunction<V>> entryTbl = endRow.get(d1);
						 Utils.removeElementFromTable(entryTbl, n);
					 }
				}
			
			// Get all predecessors of the changed node. Predecessors include
			// direct ones (the statement above, goto origins) as well as return
			// edges for which the current statement is the return site.
			Set<N> preds = new HashSet<N>();
			Set<N> exitStmts = new HashSet<N>();
			for (UpdatableWrapper<N> n0 : icfg.getExitNodesForReturnSite((UpdatableWrapper<N>) n))
				exitStmts.add((N) n0);
			preds.addAll(exitStmts);
			for (UpdatableWrapper<N> n0 : icfg.getPredsOf((UpdatableWrapper<N>) n))
				preds.add((N) n0);

			// If we have only one predecessor, there is no second path we need
			// to consider. We have already recreated all facts at the return
			// site.
			if (preds == null || preds.size() < 2)
				continue;
			edgeIdx++;
			
			for (N pred : preds)
				for (Cell<D, D, EdgeFunction<V>> cell : jumpFn.lookupByTarget(pred))
					scheduleEdgeProcessing(new PathEdge<N,D,M>(cell.getRowKey(), pred, cell.getColumnKey()));
		}
		this.operationMode = OperationMode.Compute;
		awaitCompletionComputeValuesAndShutdown(false);
		System.out.println("Phase 2: Recomputed " + edgeIdx + " join edges in "
				+ (System.nanoTime() - prePhase2) / 1E9 + " seconds");

		System.out.println("Phase 3: Processing worklist for values...");
		long beforeVals = System.nanoTime();
		val.clear();
		executor = getExecutor();
		awaitCompletionComputeValuesAndShutdown(true);
		System.out.println("Phase 3: Worklist processing done, " + propagationCount + " edges processed"
				+ " in " + (System.nanoTime() - beforeVals) / 1E9 + " seconds.");
		
		this.changedNodes = null;
	}

	/**
	 * Schedules a given list of new or expired edges for recomputation.
	 * @param newEdges The list of edges to add.
	 * @param newNodes The list of new nodes in the program graph
	 * @return A mapping from changed methods to all statements that need to
	 * be reprocessed in the method
	 */
	@SuppressWarnings("unchecked")
	private Map<M, Set<N>> updateEdges
			(Map<UpdatableWrapper<N>, List<UpdatableWrapper<N>>> newEdges,
			Set<UpdatableWrapper<N>> newNodes) {
		// Process edge insertions. Nodes are processed along with their edges
		// which implies that new unconnected nodes (unreachable statements)
		// will automatically be ignored.
		UpdatableInterproceduralCFG<N, M> icfg = (UpdatableInterproceduralCFG<N, M>) icfg();
		Map<M, Set<N>> newMethodEdges = new HashMap<M, Set<N>>(newEdges.size());
		for (UpdatableWrapper<N> srcN : newEdges.keySet()) {
			if (newNodes.contains(srcN))
				continue;
			
			UpdatableWrapper<N> loopStart = icfg.getLoopStartPointFor(srcN);

			Set<N> preds = new HashSet<N>();
			if (loopStart == null)
				preds.add((N) srcN);
			else
				preds.addAll((Collection<N>) icfg.getPredsOf(loopStart));
			
			for (N preLoop : preds) {
				// Do not propagate a node more than once
				M m = icfg().getMethodOf((N) preLoop);
				Utils.addElementToMapSet(newMethodEdges, m, preLoop);
			}
		}
		return newMethodEdges;
	}

	/**
	 * Checks whether a predecessor of the given statement has already been
	 * repropagated.
	 * @param srcNodes The set of predecessors to check
	 * @param srcN The current node from which to search upwards
	 * @return True if a predecessor of srcN in srcNodes has already beeen
	 * processed, otherwise false.
	 */
	@SuppressWarnings("unchecked")
	private boolean predecessorRepropagated(Set<N> srcNodes, N srcN) {
		if (srcNodes == null)
			return false;
		UpdatableInterproceduralCFG<N, M> icfg = (UpdatableInterproceduralCFG<N, M>) icfg();
		List<N> curNodes = new ArrayList<N>();
		Set<N> doneSet = new HashSet<N>(100);
		curNodes.addAll((Collection<N>) icfg.getPredsOf((UpdatableWrapper<N>) srcN));
		while (!curNodes.isEmpty()) {
			N n = curNodes.remove(0);
			if (!doneSet.add(n))
				continue;
			
			if (srcNodes.contains(n) && n != srcN)
				return true;
			curNodes.addAll((Collection<N>) icfg.getPredsOf((UpdatableWrapper<N>) n));
		}
		return false;
	}

	/**
	 * Awaits the completion of the exploded super graph. When complete, computes result values,
	 * shuts down the executor and returns.
	 * @param computeValues True if the values shall be computed, otherwise
	 * false. If false, only edges will be computed.
	 */
	protected void awaitCompletionComputeValuesAndShutdown(boolean computeValues) {
		{
			final long before = System.currentTimeMillis();
			//await termination of tasks
			try {
				executor.awaitCompletion();
			} catch (InterruptedException e) {
				e.printStackTrace();
			}
			durationFlowFunctionConstruction = System.currentTimeMillis() - before;
		}
		
		if(computeValues) {
			final long before = System.currentTimeMillis();
			computeValues();
			durationFlowFunctionApplication = System.currentTimeMillis() - before;
		}
		if(DEBUG)
			printStats();
		
		//ask executor to shut down;
		//this will cause new submissions to the executor to be rejected,
		//but at this point all tasks should have completed anyway
		executor.shutdown();
		//similarly here: we await termination, but this should happen instantaneously,
		//as all tasks should have completed
		try {
			executor.awaitTermination(Long.MAX_VALUE, TimeUnit.DAYS);
		} catch (InterruptedException e) {
			throw new RuntimeException(e);
		}
	}

    /**
     * Dispatch the processing of a given edge. It may be executed in a different thread.
     * @param edge the edge to process
     */
    protected void scheduleEdgeProcessing(PathEdge<N,D,M> edge){
    	executor.execute(new PathEdgeProcessingTask(edge));
    	propagationCount++;
    }
	
    /**
     * Dispatch the processing of a given value. It may be executed in a different thread.
     * @param vpt
     */
    private void scheduleValueProcessing(ValuePropagationTask vpt){
    	executor.execute(vpt);
    }
  
    /**
     * Dispatch the computation of a given value. It may be executed in a different thread.
     * @param task
     */
	private void scheduleValueComputationTask(ValueComputationTask task) {
		executor.execute(task);
	}
	
	/**
	 * Lines 13-20 of the algorithm; processing a call site in the caller's context.
	 * 
	 * For each possible callee, registers incoming call edges.
	 * Also propagates call-to-return flows and summarized callee flows within the caller. 
	 * 
	 * @param edge an edge whose target node resembles a method call
	 */
	private void processCall(PathEdge<N,D,M> edge) {
		final D d1 = edge.factAtSource();
		final N n = edge.getTarget(); // a call node; line 14...
		final D d2 = edge.factAtTarget();
		EdgeFunction<V> f = jumpFunction(edge);
		List<N> returnSiteNs = icfg().getReturnSitesOfCallAt(n);

		// We may have to erase a fact in the callees
		if (d2 == null) {
			for (N retSite : returnSiteNs)
				clearAndPropagate(d1, retSite);
			return;
		}
		
		//for each possible callee
		Set<M> callees = icfg().getCalleesOfCallAt(n);
		for(M sCalledProcN: callees) { //still line 14
			
			//compute the call-flow function
			FlowFunction<D> function = flowFunctions.getCallFlowFunction(n, sCalledProcN);
			flowFunctionConstructionCount++;
			Set<D> res = function.computeTargets(d2);
			
			//for each callee's start point(s)
			for(N sP: icfg().getStartPointsOf(sCalledProcN)) {					
				//for each result node of the call-flow function
				for(D d3: res) {
					//create initial self-loop
					propagate(d3, sP, d3, EdgeIdentity.<V>v()); //line 15
	
					//register the fact that <sp,d3> has an incoming edge from <n,d2>
					Set<Cell<N, D, EdgeFunction<V>>> endSumm;
					synchronized (incoming) {
						//line 15.1 of Naeem/Lhotak/Rodriguez
						addIncoming(sP,d3,n,d2);
						//line 15.2, copy to avoid concurrent modification exceptions by other threads
						endSumm = new HashSet<Table.Cell<N,D,EdgeFunction<V>>>(endSummary(sP, d3));						
					}
					
					//still line 15.2 of Naeem/Lhotak/Rodriguez
					//for each already-queried exit value <eP,d4> reachable from <sP,d3>,
					//create new caller-side jump functions to the return sites
					//because we have observed a potentially new incoming edge into <sP,d3>
					for(Cell<N, D, EdgeFunction<V>> entry: endSumm) {
						N eP = entry.getRowKey();
						D d4 = entry.getColumnKey();
						EdgeFunction<V> fCalleeSummary = entry.getValue();
						//for each return site
						for(N retSiteN: returnSiteNs) {
							//compute return-flow function
							FlowFunction<D> retFunction = flowFunctions.getReturnFlowFunction(n, sCalledProcN, eP, retSiteN);
							flowFunctionConstructionCount++;
							//for each target value of the function
							Set<D> targets = retFunction.computeTargets(d4);
							for(D d5: targets) {
								//update the caller-side summary function
								EdgeFunction<V> f4 = edgeFunctions.getCallEdgeFunction(n, d2, sCalledProcN, d3);
								EdgeFunction<V> f5 = edgeFunctions.getReturnEdgeFunction(n, sCalledProcN, eP, d4, retSiteN, d5);
								EdgeFunction<V> fPrime = f4.composeWith(fCalleeSummary).composeWith(f5);							
								if (operationMode == OperationMode.Update)
									clearAndPropagate(d1, retSiteN, d3, f.composeWith(fPrime));
								else
									propagate(d1, retSiteN, d5, f.composeWith(fPrime));
							}
							if (operationMode == OperationMode.Update && targets.isEmpty())
								clearAndPropagate(d1, retSiteN);
						}
					}
				}		
			}
		}
		//line 17-19 of Naeem/Lhotak/Rodriguez		
		//process intra-procedural flows along call-to-return flow functions
		for (N returnSiteN : returnSiteNs) {
			FlowFunction<D> callToReturnFlowFunction = flowFunctions.getCallToReturnFlowFunction(n, returnSiteN);
			flowFunctionConstructionCount++;
			Set<D> targets = callToReturnFlowFunction.computeTargets(d2);
			for(D d3: targets) {
				EdgeFunction<V> edgeFnE = edgeFunctions.getCallToReturnEdgeFunction(n, d2, returnSiteN, d3);
				if (operationMode == OperationMode.Update)
					clearAndPropagate(d1, returnSiteN, d3, f.composeWith(edgeFnE));
				else
					propagate(d1, returnSiteN, d3, f.composeWith(edgeFnE));
			}
			if (operationMode == OperationMode.Update && targets.isEmpty())
				clearAndPropagate(d1, returnSiteN);
		}
	}

	/**
	 * Lines 21-32 of the algorithm.	
	 * 
	 * Stores callee-side summaries.
	 * Also, at the side of the caller, propagates intra-procedural flows to return sites
	 * using those newly computed summaries.
	 * 
	 * @param edge an edge whose target node resembles a method exits
	 */
	private void processExit(PathEdge<N,D,M> edge) {
		final N n = edge.getTarget(); // an exit node; line 21...
		EdgeFunction<V> f = jumpFunction(edge);
		M methodThatNeedsSummary = icfg().getMethodOf(n);
		
		final D d1 = edge.factAtSource();
		final D d2 = edge.factAtTarget();
		
		//for each of the method's start points
		for(N sP: icfg().getStartPointsOf(methodThatNeedsSummary)) {
			//line 21.1 of Naeem/Lhotak/Rodriguez
			
			//register end-summary
			Set<Entry<N, Set<D>>> inc;
			synchronized (incoming) {
				if (d2 != null)
					addEndSummary(sP, d1, n, d2, f);
				//copy to avoid concurrent modification exceptions by other threads
				inc = new HashSet<Map.Entry<N,Set<D>>>(incoming(d1, sP));
			}
			
			//for each incoming call edge already processed
			//(see processCall(..))
			for (Entry<N,Set<D>> entry: inc) {
				//line 22
				N c = entry.getKey();
				//for each return site
				for(N retSiteC: icfg().getReturnSitesOfCallAt(c)) {
					// Do not return into a method if there is a predecessor that
					// will be changed later anyway
					if (operationMode == OperationMode.Update)
						if (predecessorRepropagated(this.changeSet.get(icfg().getMethodOf(retSiteC)), retSiteC))
							continue;
					// If we have a null fact, there is nothing to return
					if (d2 == null) {
						clearAndPropagate(d1, retSiteC);
						continue;
					}

					//compute return-flow function
					FlowFunction<D> retFunction = flowFunctions.getReturnFlowFunction(c, methodThatNeedsSummary,n,retSiteC);
					flowFunctionConstructionCount++;
					Set<D> targets = retFunction.computeTargets(d2);
					//for each incoming-call value
					for(D d4: entry.getValue()) {
						//for each target value at the return site
						//line 23
						for(D d5: targets) {
							//compute composed function
							EdgeFunction<V> f4 = edgeFunctions.getCallEdgeFunction(c, d4, icfg().getMethodOf(n), d1);
							EdgeFunction<V> f5 = edgeFunctions.getReturnEdgeFunction(c, icfg().getMethodOf(n), n, d2, retSiteC, d5);
							EdgeFunction<V> fPrime = f4.composeWith(f).composeWith(f5);
							//for each jump function coming into the call, propagate to return site using the composed function
							for(Map.Entry<D,EdgeFunction<V>> valAndFunc: jumpFn.reverseLookup(c,d4).entrySet()) {
								EdgeFunction<V> f3 = valAndFunc.getValue();
								if(!f3.equalTo(allTop)) {
									D d3 = valAndFunc.getKey();
									if (operationMode == OperationMode.Update)
										clearAndPropagate(d3, retSiteC, d5, f3.composeWith(fPrime));
									else
										propagate(d3, retSiteC, d5, f3.composeWith(fPrime));
								}
							}
						}
						if (operationMode == OperationMode.Update && targets.isEmpty())
							for(D d3 : jumpFn.reverseLookup(c,d4).keySet())
								clearAndPropagate(d3, retSiteC);
					}
				}
			}
			
			//handling for unbalanced problems where we return out of a method whose call was never processed
			if(inc.isEmpty() && followReturnsPastSeeds) {
				Set<N> callers = icfg().getCallersOf(methodThatNeedsSummary);
				for(N c: callers) {
					for(N retSiteC: icfg().getReturnSitesOfCallAt(c)) {
						FlowFunction<D> retFunction = flowFunctions.getReturnFlowFunction(c, methodThatNeedsSummary,n,retSiteC);
						flowFunctionConstructionCount++;
						Set<D> targets = retFunction.computeTargets(d2);
						for(D d5: targets) {
							EdgeFunction<V> f5 = edgeFunctions.getReturnEdgeFunction(c, icfg().getMethodOf(n), n, d2, retSiteC, d5);
							if (operationMode == OperationMode.Update)
								clearAndPropagate(d2, retSiteC, d5, f.composeWith(f5));
							else
								propagate(d2, retSiteC, d5, f.composeWith(f5));
						}
						if (operationMode == OperationMode.Update && targets.isEmpty())
							clearAndPropagate(d2, retSiteC);
					}
				}
				if(callers.isEmpty()) {
					FlowFunction<D> normalFlowFunction = flowFunctions.getNormalFlowFunction(n,n);
					flowFunctionConstructionCount++;
					normalFlowFunction.computeTargets(d2);
				}
			}
		}
	}
	
	/**
	 * Lines 33-37 of the algorithm.
	 * Simply propagate normal, intra-procedural flows.
	 * @param edge
	 */
	private void processNormalFlow(PathEdge<N,D,M> edge) {
		final D d1 = edge.factAtSource();
		final N n = edge.getTarget(); 
		final D d2 = edge.factAtTarget();
		
		// Zero fact handling
		if (d2 == null) {
			assert operationMode == OperationMode.Update;
			for (N m : icfg().getSuccsOf(edge.getTarget()))
				clearAndPropagate(d1, m);
			return;
		}
		
		EdgeFunction<V> f = jumpFunction(edge);
		for (N m : icfg().getSuccsOf(n)) {
			FlowFunction<D> flowFunction = flowFunctions.getNormalFlowFunction(n,m);
			flowFunctionConstructionCount++;
			Set<D> res = flowFunction.computeTargets(d2);
			for (D d3 : res) {
				EdgeFunction<V> fprime = f.composeWith(edgeFunctions.getNormalEdgeFunction(n, d2, m, d3));
				if (operationMode == OperationMode.Update)
					clearAndPropagate(d1, m, d3, fprime);
				else
					propagate(d1, m, d3, fprime);
			}
			if (operationMode == OperationMode.Update && res.isEmpty())
				clearAndPropagate(d1, m);
		}
	}
	
	private void propagate(D sourceVal, N target, D targetVal, EdgeFunction<V> f) {
		EdgeFunction<V> jumpFnE;
		EdgeFunction<V> fPrime;
		boolean newFunction;
		synchronized (jumpFn) {
			jumpFnE = jumpFn.reverseLookup(target, targetVal).get(sourceVal);
			if(jumpFnE==null) jumpFnE = allTop; //JumpFn is initialized to all-top (see line [2] in SRH96 paper)
			fPrime = jumpFnE.joinWith(f);
			newFunction = !fPrime.equalTo(jumpFnE);
			if(newFunction) {
				jumpFn.addFunction(sourceVal, target, targetVal, fPrime);
			}
		}

		if(newFunction) {
			PathEdge<N,D,M> edge = new PathEdge<N,D,M>(sourceVal, target, targetVal);
			scheduleEdgeProcessing(edge);

			if(DEBUG_VERBOSE) {
				if(targetVal != tabulationProblem.zeroValue()) {			
					StringBuilder result = new StringBuilder();
					result.append("EDGE:  <");
					result.append(icfg().getMethodOf(target));
					result.append(",");
					result.append(sourceVal);
					result.append("> -> <");
					result.append(target);
					result.append(",");
					result.append(targetVal);
					result.append("> - ");
					result.append(fPrime);
					System.err.println(result.toString());
				}
			}
		}
	}
	
	private void clearAndPropagate(D sourceVal, N target, D targetVal, EdgeFunction<V> f) {
		assert operationMode == OperationMode.Update;
		
		synchronized (jumpSave) {
			Set<D> savedFacts = this.jumpSave.get(target);
			if (savedFacts == null || !savedFacts.contains(sourceVal)) {
				// We have not processed this edge yet
				Utils.addElementToMapSet(this.jumpSave, target, sourceVal);
	
				// Delete the original facts
				synchronized (jumpFn) {
					for (D d : new HashSet<D>(this.jumpFn.forwardLookup(sourceVal, target).keySet()))
						this.jumpFn.removeFunction(sourceVal, target, d);
				}
				synchronized (changedNodes) {
					this.changedNodes.add(target);
				}
			}
		}
		propagate(sourceVal, target, targetVal, f);
	}

	private void clearAndPropagate(D sourceVal, N target) {
		assert operationMode == OperationMode.Update;
		
		synchronized (jumpSave) {
			Set<D> savedFacts = this.jumpSave.get(target);
			if (savedFacts == null || !savedFacts.contains(sourceVal)) {
				// We have not processed this edge yet
				Utils.addElementToMapSet(this.jumpSave, target, sourceVal);
	
				// Delete the original facts
				synchronized (jumpFn) {
					for (D d : new HashSet<D>(this.jumpFn.forwardLookup(sourceVal, target).keySet()))
						this.jumpFn.removeFunction(sourceVal, target, d);
				}
				synchronized (changedNodes) {
					this.changedNodes.add(target);
					scheduleEdgeProcessing(new PathEdge<N, D, M>(sourceVal, target, null));
				}
			}
		}
	}

	/**
	 * Computes the final values for edge functions.
	 */
	private void computeValues() {	
		//Phase II(i)
		long beforePhase1 = System.nanoTime();
		D zeroValue = this.tabulationProblem.zeroValue();
		for(N startPoint: initialSeeds) {
			setVal(startPoint, zeroValue, valueLattice.bottomElement());
			Pair<N, D> superGraphNode = new Pair<N,D>(startPoint, zeroValue); 
			scheduleValueProcessing(new ValuePropagationTask(superGraphNode));
		}
		
		//await termination of tasks
		try {
			executor.awaitCompletion();
		} catch (InterruptedException e) {
			e.printStackTrace();
		}
		System.out.println("Phase V1 took " + (System.nanoTime() - beforePhase1) / 1E9 + " seconds.");
		
		//Phase II(ii)
		//we create an array of all nodes and then dispatch fractions of this array to multiple threads
		long beforePhase2 = System.nanoTime();
		Set<N> allNonCallStartNodes = icfg().allNonCallStartNodes();
		@SuppressWarnings("unchecked")
		N[] nonCallStartNodesArray = (N[]) new Object[allNonCallStartNodes.size()];
		int i=0;
		for (N n : allNonCallStartNodes) {
			nonCallStartNodesArray[i] = n;
			i++;
		}
		//No need to keep track of the number of tasks scheduled here, since we call shutdown
		for(int t=0;t<numThreads; t++) {
			ValueComputationTask task = new ValueComputationTask(nonCallStartNodesArray, t);
			scheduleValueComputationTask(task);
		}
		//await termination of tasks
		try {
			executor.awaitCompletion();
		} catch (InterruptedException e) {
			e.printStackTrace();
		}
		System.out.println("Phase V2 took " + (System.nanoTime() - beforePhase2) / 1E9 + " seconds.");
	}

	private void propagateValueAtStart(Pair<N, D> nAndD, N n) {
		D d = nAndD.getO2();		
		M p = icfg().getMethodOf(n);
		for(N c: icfg().getCallsFromWithin(p)) {					
			Set<Entry<D, EdgeFunction<V>>> entries; 
			synchronized (jumpFn) {
				entries = jumpFn.forwardLookup(d,c).entrySet();
				for(Map.Entry<D,EdgeFunction<V>> dPAndFP: entries) {
					D dPrime = dPAndFP.getKey();
					EdgeFunction<V> fPrime = dPAndFP.getValue();
					N sP = n;
					propagateValue(c,dPrime,fPrime.computeTarget(val(sP,d)));
					flowFunctionApplicationCount++;
				}
			}
		}
	}
	
	private void propagateValueAtCall(Pair<N, D> nAndD, N n) {
		D d = nAndD.getO2();
		for(M q: icfg().getCalleesOfCallAt(n)) {
			FlowFunction<D> callFlowFunction = flowFunctions.getCallFlowFunction(n, q);
			flowFunctionConstructionCount++;
			for(D dPrime: callFlowFunction.computeTargets(d)) {
				EdgeFunction<V> edgeFn = edgeFunctions.getCallEdgeFunction(n, d, q, dPrime);
				for(N startPoint: icfg().getStartPointsOf(q)) {
					propagateValue(startPoint,dPrime, edgeFn.computeTarget(val(n,d)));
					flowFunctionApplicationCount++;
				}
			}
		}
	}
	
	private void propagateValue(N nHashN, D nHashD, V v) {
		synchronized (val) {
			V valNHash = val(nHashN, nHashD);
			V vPrime = valueLattice.join(valNHash,v);
			if(!vPrime.equals(valNHash)) {
				setVal(nHashN, nHashD, vPrime);
				scheduleValueProcessing(new ValuePropagationTask(new Pair<N,D>(nHashN,nHashD)));
			}
		}
	}

	private V val(N nHashN, D nHashD){ 
		V l = val.get(nHashN, nHashD);
		if(l==null) return valueLattice.topElement(); //implicitly initialized to top; see line [1] of Fig. 7 in SRH96 paper
		else return l;
	}
	
	private void setVal(N nHashN, D nHashD,V l){ 
		val.put(nHashN, nHashD,l);
		if(DEBUG_VERBOSE)
			System.err.println("VALUE: "+icfg().getMethodOf(nHashN)+" "+nHashN+" "+nHashD+ " " + l);
	}

	private EdgeFunction<V> jumpFunction(PathEdge<N, D, M> edge) {
		synchronized (jumpFn) {
			EdgeFunction<V> function = jumpFn.forwardLookup(edge.factAtSource(), edge.getTarget()).get(edge.factAtTarget());
			if(function==null) return allTop; //JumpFn initialized to all-top, see line [2] in SRH96 paper
			return function;
		}
	}

	private Set<Cell<N, D, EdgeFunction<V>>> endSummary(N sP, D d3) {
		Table<N, D, EdgeFunction<V>> map = endSummary.get(sP, d3);
		if(map==null) return Collections.emptySet();
		return map.cellSet();
	}

	private void addEndSummary(N sP, D d1, N eP, D d2, EdgeFunction<V> f) {
		Table<N, D, EdgeFunction<V>> summaries = endSummary.get(sP, d1);
		if(summaries==null) {
			summaries = HashBasedTable.create();
			endSummary.put(sP, d1, summaries);
		}
		//note: at this point we don't need to join with a potential previous f
		//because f is a jump function, which is already properly joined
		//within propagate(..)
		summaries.put(eP,d2,f);
	}	
	
	private Set<Entry<N, Set<D>>> incoming(D d1, N sP) {
		Map<N, Set<D>> map = incoming.get(sP, d1);
		if(map==null) return Collections.emptySet();
		return map.entrySet();		
	}
	
	private void addIncoming(N sP, D d3, N n, D d2) {
		Map<N, Set<D>> summaries = incoming.get(sP, d3);
		if(summaries==null) {
			summaries = new HashMap<N, Set<D>>();
			incoming.put(sP, d3, summaries);
		}
		Set<D> set = summaries.get(n);
		if(set==null) {
			set = new HashSet<D>();
			summaries.put(n,set);
		}
		set.add(d2);
	}	
	
	/**
	 * Returns the V-type result for the given value at the given statement. 
	 */
	public V resultAt(N stmt, D value) {
		return val.get(stmt, value);
	}
	
	/**
	 * Returns the resulting environment for the given statement.
	 * The artificial zero value is automatically stripped.
	 */
	public Map<D,V> resultsAt(N stmt) {
		//filter out the artificial zero-value
		return Maps.filterKeys(val.row(stmt), new Predicate<D>() {

			public boolean apply(D val) {
				return val != tabulationProblem.zeroValue();
			}
		});
	}
	
	/**
	 * Factory method for this solver's thread-pool executor.
	 */
	protected CountingThreadPoolExecutor getExecutor() {
		return new CountingThreadPoolExecutor(1, this.numThreads, 30, TimeUnit.SECONDS, new LinkedBlockingQueue<Runnable>());
	}

	public void printStats() {
		if(DEBUG) {
			if(ffCache!=null)
				ffCache.printStats();
			if(efCache!=null)
				efCache.printStats();
		} else {
			System.err.println("No statistics were collected, as DEBUG is disabled.");
		}
	}
	
	private class PathEdgeProcessingTask implements Runnable {
		private final PathEdge<N, D, M> edge;

		public PathEdgeProcessingTask(PathEdge<N, D, M> edge) {
			this.edge = edge;
		}

		public void run() {
			if(icfg().isCallStmt(edge.getTarget())) {
				processCall(edge);
			} else {
				//note that some statements, such as "throw" may be
				//both an exit statement and a "normal" statement
				if(icfg().isExitStmt(edge.getTarget())) {
					processExit(edge);
				}
				if(!icfg().getSuccsOf(edge.getTarget()).isEmpty()) {
					processNormalFlow(edge);
				}
			}
		}
	}
	
	private class ValuePropagationTask implements Runnable {
		private final Pair<N, D> nAndD;

		public ValuePropagationTask(Pair<N,D> nAndD) {
			this.nAndD = nAndD;
		}

		public void run() {
			N n = nAndD.getO1();
			if(icfg().isStartPoint(n) ||
				initialSeeds.contains(n)) { 		//our initial seeds are not necessarily method-start points but here they should be treated as such
				propagateValueAtStart(nAndD, n);
			}
			if(icfg().isCallStmt(n)) {
				propagateValueAtCall(nAndD, n);
			}
		}
	}
	
	private class ValueComputationTask implements Runnable {
		private final N[] values;
		final int num;

		public ValueComputationTask(N[] values, int num) {
			this.values = values;
			this.num = num;
		}

		public void run() {
			int sectionSize = (int) Math.floor(values.length / numThreads) + numThreads;
			for(int i = sectionSize * num; i < Math.min(sectionSize * (num+1),values.length); i++) {
				N n = values[i];
				for(N sP: icfg().getStartPointsOf(icfg().getMethodOf(n))) {
					Set<Cell<D, D, EdgeFunction<V>>> lookupByTarget;
					lookupByTarget = jumpFn.lookupByTarget(n);
					for(Cell<D, D, EdgeFunction<V>> sourceValTargetValAndFunction : lookupByTarget) {
						D dPrime = sourceValTargetValAndFunction.getRowKey();
						D d = sourceValTargetValAndFunction.getColumnKey();
						EdgeFunction<V> fPrime = sourceValTargetValAndFunction.getValue();
						synchronized (val) {
							setVal(n,d,valueLattice.join(val(n,d),fPrime.computeTarget(val(sP,dPrime))));
						}
						flowFunctionApplicationCount++;
					}
				}
			}
		}
	}
	
	/**
	 * Sets the performance optimization strategy to be used in the solver.
	 * These modes model the tradeoffs (e.g. time/memory) made in the solver.
	 * @param optimizationMode The optimization mode to use
	 */
	public void setOptimizationMode(OptimizationMode optimizationMode) {
		this.optimizationMode = optimizationMode;
	}
	
	/**
	 * Gets the optimization mode used by this solver
	 * @return The optimization mode used by this solver
	 */
	public OptimizationMode getOptimizationMode() {
		return this.optimizationMode;
	}
	
	/**
	 * Clears the results computed by this solver
	 */
	public void clearResults() {
		this.jumpFn.clear();
		this.endSummary.clear();
		this.incoming.clear();
		this.val.clear();
		
		this.efCache.invalidateAll();
		this.ffCache.invalidateAll();
		
		this.propagationCount = 0;
	}

}
