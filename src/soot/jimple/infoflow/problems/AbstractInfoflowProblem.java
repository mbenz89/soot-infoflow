/*******************************************************************************
 * Copyright (c) 2012 Secure Software Engineering Group at EC SPRIDE.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the GNU Lesser Public License v2.1
 * which accompanies this distribution, and is available at
 * http://www.gnu.org/licenses/old-licenses/gpl-2.0.html
 * 
 * Contributors: Christian Fritz, Steven Arzt, Siegfried Rasthofer, Eric
 * Bodden, and others.
 ******************************************************************************/
package soot.jimple.infoflow.problems;

import heros.InterproceduralCFG;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import soot.ArrayType;
import soot.IntType;
import soot.Local;
import soot.LongType;
import soot.PrimType;
import soot.Scene;
import soot.SootClass;
import soot.SootMethod;
import soot.Type;
import soot.Unit;
import soot.Value;
import soot.jimple.Constant;
import soot.jimple.DefinitionStmt;
import soot.jimple.InstanceFieldRef;
import soot.jimple.StaticFieldRef;
import soot.jimple.Stmt;
import soot.jimple.infoflow.data.Abstraction;
import soot.jimple.infoflow.data.AccessPath;
import soot.jimple.infoflow.handlers.TaintPropagationHandler;
import soot.jimple.infoflow.nativ.DefaultNativeCallHandler;
import soot.jimple.infoflow.nativ.NativeCallHandler;
import soot.jimple.infoflow.solver.IInfoflowCFG;
import soot.jimple.infoflow.solver.IInfoflowSolver;
import soot.jimple.infoflow.source.ISourceSinkManager;
import soot.jimple.infoflow.taintWrappers.ITaintPropagationWrapper;
import soot.jimple.infoflow.util.DataTypeHandler;
import soot.jimple.toolkits.ide.DefaultJimpleIFDSTabulationProblem;
/**
 * abstract super class which 
 * 	- concentrates functionality used by InfoflowProblem and BackwardsInfoflowProblem
 *  - contains helper functions which should not pollute the naturally large InfofflowProblems
 *
 */
public abstract class AbstractInfoflowProblem extends DefaultJimpleIFDSTabulationProblem<Abstraction, InterproceduralCFG<Unit, SootMethod>> {

	protected final Map<Unit, Set<Abstraction>> initialSeeds = new HashMap<Unit, Set<Abstraction>>();
	protected ITaintPropagationWrapper taintWrapper;
	
	protected final NativeCallHandler ncHandler = new DefaultNativeCallHandler();
	protected final ISourceSinkManager sourceSinkManager;

    protected final Logger logger = LoggerFactory.getLogger(getClass());

    protected boolean enableImplicitFlows = false;
	protected boolean enableStaticFields = true;
	protected boolean enableExceptions = true;
	protected boolean flowSensitiveAliasing = true;
	protected boolean enableTypeChecking = true;

	protected boolean inspectSources = false;
	protected boolean inspectSinks = false;

	Abstraction zeroValue = null;
	
	protected IInfoflowSolver solver = null;
	
	protected boolean stopAfterFirstFlow = false;
	
	protected Set<TaintPropagationHandler> taintPropagationHandlers = new HashSet<TaintPropagationHandler>();
	
	public AbstractInfoflowProblem(InterproceduralCFG<Unit, SootMethod> icfg,
			ISourceSinkManager sourceSinkManager) {
		super(icfg);
		this.sourceSinkManager = sourceSinkManager;
	}
	
	protected boolean canCastType(Type destType, Type sourceType) {
		if (!enableTypeChecking)
			return true;
		
		// If we don't have a source type, we generally allow the cast
		if (sourceType == null)
			return true;
		
		if (Scene.v().getFastHierarchy().canStoreType(destType, sourceType) // cast-up, i.e. Object to String
				|| Scene.v().getFastHierarchy().canStoreType(sourceType, destType)) // cast-down, i.e. String to Object
			return true;
		
		if (destType instanceof PrimType && sourceType instanceof PrimType)
			if (sourceType instanceof LongType && destType instanceof IntType
					|| destType instanceof LongType && sourceType instanceof IntType)
				return true;
			
		return false;
	}
		
	protected boolean hasCompatibleTypesForCall(AccessPath apBase, SootClass dest) {
		if (!enableTypeChecking)
			return true;

		// Cannot invoke a method on a primitive type
		if (apBase.getType() instanceof PrimType)
			return false;
		// Cannot invoke a method on an array
		if (apBase.getType() instanceof ArrayType)
			return dest.getName().equals("java.lang.Object");
		
		return Scene.v().getFastHierarchy().canStoreType(apBase.getType(), dest.getType())
				|| Scene.v().getFastHierarchy().canStoreType(dest.getType(), apBase.getType());
	}

	public void setSolver(IInfoflowSolver solver) {
		this.solver = solver;
	}
	
	public void setZeroValue(Abstraction zeroValue) {
		this.zeroValue = zeroValue;
	}

	/**
	 * we need this option as we start directly at the sources, but need to go 
	 * backward in the call stack
	 */
	@Override
	public boolean followReturnsPastSeeds(){
		return true;
	}
	
	public void setTaintWrapper(ITaintPropagationWrapper wrapper){
		taintWrapper = wrapper;
	}
		
	/**
	 * Sets whether the information flow analysis shall stop after the first
	 * flow has been found
	 * @param stopAfterFirstFlow True if the analysis shall stop after the
	 * first flow has been found, otherwise false.
	 */
	public void setStopAfterFirstFlow(boolean stopAfterFirstFlow) {
		this.stopAfterFirstFlow = stopAfterFirstFlow;
	}
		
	/**
	 * Sets whether the solver shall consider implicit flows.
	 * @param enableImplicitFlows True if implicit flows shall be considered,
	 * otherwise false.
	 */
	public void setEnableImplicitFlows(boolean enableImplicitFlows) {
		this.enableImplicitFlows = enableImplicitFlows;
	}

	/**
	 * Sets whether the solver shall consider assignments to static fields.
	 * @param enableStaticFields True if assignments to static fields shall be
	 * tracked, otherwise false
	 */
	public void setEnableStaticFieldTracking(boolean enableStaticFields) {
		this.enableStaticFields = enableStaticFields;
	}

	/**
	 * Sets whether the solver shall track taints over exceptions, i.e. throw
	 * new RuntimeException(secretData).
	 * @param enableExceptions True if taints in thrown exception objects shall
	 * be tracked.
	 */
	public void setEnableExceptionTracking(boolean enableExceptions) {
		this.enableExceptions = enableExceptions;
	}

	/**
	 * Sets whether the solver shall use flow sensitive aliasing. This makes
	 * the analysis more precise, but also requires more time.
	 * @param flowSensitiveAliasing True if flow sensitive aliasing shall be
	 * used, otherwise false
	 */
	public void setFlowSensitiveAliasing(boolean flowSensitiveAliasing) {
		this.flowSensitiveAliasing = flowSensitiveAliasing;
	}
	
	/**
	 * Sets whether type checking shall be done on casts and method calls
	 * @param enableTypeChecking True if type checking shall be performed,
	 * otherwise false
	 */
	public void setEnableTypeChecking(boolean enableTypeChecking) {
		this.enableTypeChecking = enableTypeChecking;
	}

	@Override
	public Abstraction createZeroValue() {
		if (zeroValue == null)
			zeroValue = Abstraction.getZeroAbstraction(flowSensitiveAliasing);
		return zeroValue;
	}

	/**
	 * Gets whether the given method is an entry point, i.e. one of the initial
	 * seeds belongs to the given method
	 * @param sm The method to check
	 * @return True if the given method is an entry point, otherwise false
	 */
	protected boolean isInitialMethod(SootMethod sm) {
		for (Unit u : this.initialSeeds.keySet())
			if (interproceduralCFG().getMethodOf(u) == sm)
				return true;
		return false;
	}
	
	@Override
	public Map<Unit, Set<Abstraction>> initialSeeds() {
		return initialSeeds;
	}
	
	/**
	 * performance improvement: since we start directly at the sources, we do not 
	 * need to generate additional taints unconditionally
	 */
	@Override
	public boolean autoAddZero() {
		return false;
	}
	
	/**
	 * default: inspectSources is set to true, this means sources are analyzed as well.
	 * If inspectSources is set to false, then the analysis does not propagate values into 
	 * the source method. 
	 * @param inspect boolean that determines the inspectSource option
	 */
	public void setInspectSources(boolean inspect){
		inspectSources = inspect;
	}

	/**
	 * default: inspectSinks is set to true, this means sinks are analyzed as well.
	 * If inspectSinks is set to false, then the analysis does not propagate values into 
	 * the sink method. 
	 * @param inspect boolean that determines the inspectSink option
	 */
	public void setInspectSinks(boolean inspect){
		inspectSinks = inspect;
	}
	
	/**
	 * we cannot rely just on "real" heap objects, but must also inspect locals because of Jimple's representation ($r0 =... )
	 * @param val the value which gets tainted
	 * @param source the source from which the taints comes from. Important if not the value, but a field is tainted
	 * @return true if a reverseFlow should be triggered or an inactive taint should be propagated (= resulting object is stored in heap = alias)
	 */
	protected boolean triggerInaktiveTaintOrReverseFlow(Stmt stmt, Abstraction source){
		if (stmt == null || source.getAccessPath().isEmpty())
			return false;
		
		if (stmt instanceof DefinitionStmt) {
			DefinitionStmt defStmt = (DefinitionStmt) stmt;
			// If the left side is overwritten completely, we do not need to
			// look for aliases. This also covers strings.
			if (defStmt.getLeftOp() instanceof Local
					&& defStmt.getLeftOp() == source.getAccessPath().getPlainValue())
				return false;

			// Primitive types or constants do not have aliases
			if (defStmt.getLeftOp().getType() instanceof PrimType)
				return false;
			if (defStmt.getLeftOp() instanceof Constant)
				return false;
			
			// If the left side is a field or array reference (which is not
			// overwritten completely), we must look for aliases.
			if (DataTypeHandler.isFieldRefOrArrayRef(defStmt.getLeftOp()))
				return true;
		}
		
		return source.getAccessPath().isInstanceFieldRef()
				|| source.getAccessPath().isStaticFieldRef();
	}
	
	/**
	 * Checks whether the given base value matches the base of the given
	 * taint abstraction
	 * @param baseValue The value to check
	 * @param source The taint abstraction to check
	 * @return True if the given value has the same base value as the given
	 * taint abstraction, otherwise false
	 */
	protected boolean baseMatches(final Value baseValue, Abstraction source) {
		if (baseValue instanceof Local) {
			if (baseValue.equals(source.getAccessPath().getPlainValue()))
				return true;
		}
		else if (baseValue instanceof InstanceFieldRef) {
			InstanceFieldRef ifr = (InstanceFieldRef) baseValue;
			if (ifr.getBase().equals(source.getAccessPath().getPlainValue())
					&& ifr.getField().equals(source.getAccessPath().getFirstField()))
				return true;
		}
		else if (baseValue instanceof StaticFieldRef) {
			StaticFieldRef sfr = (StaticFieldRef) baseValue;
			if (sfr.getField().equals(source.getAccessPath().getFirstField()))
				return true;
		}
		return false;
	}
	
	@Override
	public IInfoflowCFG interproceduralCFG() {
		return (IInfoflowCFG) super.interproceduralCFG();
	}
	
	/**
	 * Adds the given initial seeds to the information flow problem
	 * @param unit The unit to be considered as a seed
	 * @param seeds The abstractions with which to start at the given seed
	 */
	public void addInitialSeeds(Unit unit, Set<Abstraction> seeds) {
		if (this.initialSeeds.containsKey(unit))
			this.initialSeeds.get(unit).addAll(seeds);
		else
			this.initialSeeds.put(unit, new HashSet<Abstraction>(seeds));
	}
	
	/**
	 * Gets whether this information flow problem has initial seeds
	 * @return True if this information flow problem has initial seeds,
	 * otherwise false
	 */
	public boolean hasInitialSeeds() {
		return !this.initialSeeds.isEmpty();
	}

	/**
	 * Gets the initial seeds with which this information flow problem has been
	 * configured
	 * @return The initial seeds with which this information flow problem has
	 * been configured.
	 */
	public Map<Unit, Set<Abstraction>> getInitialSeeds() {
		return this.initialSeeds;
	}
	
	/**
	 * Adds a handler which is invoked whenever a taint is propagated
	 * @param handler The handler to be invoked when propagating taints
	 */
	public void addTaintPropagationHandler(TaintPropagationHandler handler) {
		this.taintPropagationHandlers.add(handler);
	}
	
}
