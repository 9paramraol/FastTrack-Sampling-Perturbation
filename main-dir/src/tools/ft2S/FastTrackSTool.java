/******************************************************************************
 *
 * Copyright (c) 2016, Cormac Flanagan (University of California, Santa Cruz) and Stephen Freund
 * (Williams College)
 *
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without modification, are permitted
 * provided that the following conditions are met:
 *
 * Redistributions of source code must retain the above copyright notice, this list of conditions
 * and the following disclaimer.
 *
 * Redistributions in binary form must reproduce the above copyright notice, this list of conditions
 * and the following disclaimer in the documentation and/or other materials provided with the
 * distribution.
 *
 * Neither the names of the University of California, Santa Cruz and Williams College nor the names
 * of its contributors may be used to endorse or promote products derived from this software without
 * specific prior written permission.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND ANY EXPRESS OR
 * IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED WARRANTIES OF MERCHANTABILITY AND
 * FITNESS FOR A PARTICULAR PURPOSE ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT HOLDER OR
 * CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL
 * DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE,
 * DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY,
 * WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY
 * WAY OUT OF THE USE OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 *
 ******************************************************************************/

package tools.fasttrack;

import java.util.*;
import java.lang.*;
import acme.util.Assert;
import acme.util.Util;
import acme.util.count.AggregateCounter;
import acme.util.count.ThreadLocalCounter;
import acme.util.decorations.Decoration;
import acme.util.decorations.DecorationFactory;
import acme.util.decorations.DecorationFactory.Type;
import acme.util.decorations.DefaultValue;
import acme.util.decorations.NullDefault;
import acme.util.io.XMLWriter;
import acme.util.option.CommandLine;
import acme.util.option.CommandLineOption;
import rr.RRMain;
import rr.annotations.Abbrev;
import rr.barrier.BarrierEvent;
import rr.barrier.BarrierListener;
import rr.barrier.BarrierMonitor;
import rr.error.ErrorMessage;
import rr.error.ErrorMessages;
import rr.event.AccessEvent;
import rr.event.AccessEvent.Kind;
import rr.event.AcquireEvent;
import rr.event.ArrayAccessEvent;
import rr.event.ClassAccessedEvent;
import rr.event.ClassInitializedEvent;
import rr.event.FieldAccessEvent;
import rr.event.JoinEvent;
import rr.event.NewThreadEvent;
import rr.event.ReleaseEvent;
import rr.event.StartEvent;
import rr.event.VolatileAccessEvent;
import rr.event.WaitEvent;
import rr.instrument.classes.ArrayAllocSiteTracker;

import rr.meta.ArrayAccessInfo;
import rr.meta.ClassInfo;
import rr.meta.FieldInfo;
import rr.meta.MetaDataInfoMaps;
import rr.meta.MethodInfo;
import rr.meta.OperationInfo;
import rr.meta.SourceLocation;
import rr.state.ShadowLock;
import rr.state.ShadowThread;
import rr.state.ShadowVar;
import rr.state.ShadowVolatile;
import rr.tool.RR;
import rr.tool.Tool;
import tools.util.Epoch;
import tools.util.VectorClock;

/*
 * A revised FastTrack Tool. This makes several improvements over the original: - Simpler
 * synchronization scheme for VarStates. (The old optimistic scheme no longer has a performance
 * benefit and was hard to get right.) - Rephrased rules to: - include a Read-Shared-Same-Epoch
 * test. - eliminate an unnecessary update on joins (this was just for the proof). - remove the
 * Read-Shared to Exclusive transition. The last change makes the correctness argument easier and
 * that transition had little to no performance impact in practice. - Properly replays events when
 * the fast paths detect an error in all cases. - Supports long epochs for larger clock values. -
 * Handles tid reuse more precisely. The performance over the JavaGrande and DaCapo benchmarks is
 * more or less identical to the old implementation (within ~1% overall in our tests).
 */
@Abbrev("FT2S")
public class FastTrackSTool extends Tool implements BarrierListener<FTBarrierState> {

    private static final boolean COUNT_OPERATIONS = RRMain.slowMode();
    private static final int INIT_VECTOR_CLOCK_SIZE = 4;

    private static final Object printLock = new Object();
    /** --- the extra vatiables for the samling part */
    public static final CommandLineOption<Integer> samplingRate = CommandLine
            .makeInteger("samplingrate", 100, CommandLineOption.Kind.EXPERIMENTAL, "sampling_rate");

    public static final CommandLineOption<String> burstLevel = CommandLine
            .makeString("burstLvl", "thread", CommandLineOption.Kind.EXPERIMENTAL, "global or local level");

    public static final CommandLineOption<String> samplingScheme = CommandLine
            .makeString("samplingscheme", "count", CommandLineOption.Kind.EXPERIMENTAL, "sampling_scheme");

    public static final CommandLineOption<Integer> samplingType = CommandLine
            .makeInteger("samplingtype", 0, CommandLineOption.Kind.EXPERIMENTAL, "sampling_type");

    public static final CommandLineOption<Integer> minSampling = CommandLine
            .makeInteger("minsampling", 0, CommandLineOption.Kind.EXPERIMENTAL, "min_sampling");

    public static final CommandLineOption<Integer> decrementRate = CommandLine
            .makeInteger("decRate", 0, CommandLineOption.Kind.EXPERIMENTAL, "dec_rate");

    public static final CommandLineOption<Integer> burstLen = CommandLine
            .makeInteger("burstlen", 0, CommandLineOption.Kind.EXPERIMENTAL, "burst_len");

    // for the global sampling 'count'
    public static boolean setFlag; private static final Object setFlagLock = new Object();

    public static int globalCounter; private static final Object globalCntrLock = new Object();
    public static boolean[] globalPermu;

    // for the thread local sampling 'count'
    public static int[] threadCounter;
    public static boolean[][] threadPermu;

    // for the 'adaptive' sampling
    public static int maxSamplingLvl;
    public static boolean[][] adaptivPermu;
    public static int  burstLength;
    public static int decRate;
    public static int minSamp;
    public static boolean burstLvl;
    /** -------------------------------------------- */

    public final ErrorMessage<FieldInfo> fieldErrors = ErrorMessages
            .makeFieldErrorMessage("FastTrack");
    public final ErrorMessage<ArrayAccessInfo> arrayErrors = ErrorMessages
            .makeArrayErrorMessage("FastTrack");

    private final VectorClock maxEpochPerTid = new VectorClock(INIT_VECTOR_CLOCK_SIZE);

    // CS636: Every class object would have a vector clock. classInitTime is the Decoration which
    // stores ClassInfo (as a key) and corresponding vector clock for that class (as a value).
    // guarded by classInitTime
    public static final Decoration<ClassInfo, VectorClock> classInitTime = MetaDataInfoMaps
            .getClasses().makeDecoration("FastTrack:ClassInitTime", Type.MULTIPLE,
                    new DefaultValue<ClassInfo, VectorClock>() {
                        public VectorClock get(ClassInfo st) {
                            return new VectorClock(INIT_VECTOR_CLOCK_SIZE);
                        }
                    });

    public static VectorClock getClassInitTime(ClassInfo ci) {
        synchronized (classInitTime) {
            return classInitTime.get(ci);
        }
    }


    public FastTrackSTool(final String name, final Tool next, CommandLine commandLine) {

        super(name, next, commandLine);
        /**----------------------------------*/
        commandLine.add(samplingRate);
        commandLine.add(samplingScheme);
        commandLine.add(samplingType);
        commandLine.add(minSampling);
        commandLine.add(decrementRate);
        commandLine.add(burstLen);
        commandLine.add(burstLevel);
        setFlag = true;
        /**-----------------------------------*/
        new BarrierMonitor<FTBarrierState>(this, new DefaultValue<Object, FTBarrierState>() {
            public FTBarrierState get(Object k) {
                return new FTBarrierState(k, INIT_VECTOR_CLOCK_SIZE);
            }
        });
    }

    /*
     * Shadow State: St.E -- epoch decoration on ShadowThread - Thread-local. Never access from a
     * different thread St.V -- VectorClock decoration on ShadowThread - Thread-local while thread
     * is running. - The thread starting t may access st.V before the start. - Any thread joining on
     * t may read st.V after the join. Sm.V -- FTLockState decoration on ShadowLock - See
     * FTLockState for synchronization rules. Sx.R,Sx.W,Sx.V -- FTSVarState objects - See FTSVarState
     * for synchronization rules. Svx.V -- FTVolatileState decoration on ShadowVolatile (serves same
     * purpose as L for volatiles) - See FTVolatileState for synchronization rules. Sb.V --
     * FTBarrierState decoration on Barriers - See FTBarrierState for synchronization rules.
     */

    // invariant: st.E == st.V(st.tid)
    protected static int/* epoch */ ts_get_E(ShadowThread st) {
        Assert.panic("Bad");
        return -1;
    }

    protected static void ts_set_E(ShadowThread st, int/* epoch */ e) {
        Assert.panic("Bad");
    }

    protected static VectorClock ts_get_V(ShadowThread st) {
        Assert.panic("Bad");
        return null;
    }

    protected static void ts_set_V(ShadowThread st, VectorClock V) {
        Assert.panic("Bad");
    }


    protected void maxAndIncEpochAndCV(ShadowThread st, VectorClock other, OperationInfo info) {
        final int tid = st.getTid();
        final VectorClock tV = ts_get_V(st);
        tV.max(other);
        tV.tick(tid);
        ts_set_E(st, tV.get(tid));
    }

    protected void maxEpochAndCV(ShadowThread st, VectorClock other, OperationInfo info) {
        final int tid = st.getTid();
        final VectorClock tV = ts_get_V(st);
        tV.max(other);
        ts_set_E(st, tV.get(tid));
    }

    protected void incEpochAndCV(ShadowThread st, OperationInfo info) {
        final int tid = st.getTid();
        final VectorClock tV = ts_get_V(st);
        tV.tick(tid);
        ts_set_E(st, tV.get(tid));
    }

    /** FTLockState extends vector clock in some way */
    static final Decoration<ShadowLock, FTLockState> lockVs = ShadowLock.makeDecoration(
            "FastTrack:ShadowLock", DecorationFactory.Type.MULTIPLE,
            new DefaultValue<ShadowLock, FTLockState>() {
                public FTLockState get(final ShadowLock lock) {
                    return new FTLockState(lock, INIT_VECTOR_CLOCK_SIZE);
                }
            });

    // only call when ld.peer() is held
    static final FTLockState getV(final ShadowLock ld) {
        return lockVs.get(ld);
    }

    static final Decoration<ShadowVolatile, FTVolatileState> volatileVs = ShadowVolatile
            .makeDecoration("FastTrack:shadowVolatile", DecorationFactory.Type.MULTIPLE,
                    new DefaultValue<ShadowVolatile, FTVolatileState>() {
                        public FTVolatileState get(final ShadowVolatile vol) {
                            return new FTVolatileState(vol, INIT_VECTOR_CLOCK_SIZE);
                        }
                    });

    // only call when we are in an event handler for the volatile field.
    protected static final FTVolatileState getV(final ShadowVolatile ld) {
        return volatileVs.get(ld);
    }


    @Override
    public ShadowVar makeShadowVar(final AccessEvent event) {
        if (event.getKind() == Kind.VOLATILE) {
            final ShadowThread st = event.getThread();
            final VectorClock volV = getV(((VolatileAccessEvent) event).getShadowVolatile());
            volV.max(ts_get_V(st));
            return super.makeShadowVar(event);
        } else {
            FTVarState temp = new FTVarState(event.isWrite(), ts_get_E(event.getThread()));
            if(samplingScheme.get().equals("adaptive")){
                temp.CreateThreadAccessCount(RR.maxTidOption.get().intValue(),burstLen.get().intValue());
            }
            temp.initLoctnInfo(RR.maxTidOption.get());
            return temp;
        }

    }

    @Override
    public void create(NewThreadEvent event) {
        final ShadowThread st = event.getThread();
        if (ts_get_V(st) == null) {
            final int tid = st.getTid();
            final VectorClock tV = new VectorClock(INIT_VECTOR_CLOCK_SIZE);
            ts_set_V(st, tV);
            synchronized (maxEpochPerTid) {
                final int/* epoch */ epoch = maxEpochPerTid.get(tid) + 1;
                tV.set(tid, epoch);
                ts_set_E(st, epoch);
            }
            if(samplingScheme.get().equals("count")){
                // count, 1, global sampling
                if(samplingType.get()==1 && setFlag){
                    synchronized(globalCntrLock){
                        globalCounter = 0;
                        globalPermu = arrayPermute(100,samplingRate.get().intValue());
                    }
                    setFlag = false;
                }
                //count, 2 thread level sampling
                else if(samplingType.get()==2){
                    if(setFlag) {
                        synchronized (setFlagLock) {
                            if (setFlag) {
                                threadCounter = new int[RR.maxTidOption.get()];
                                for (int i = 0; i < RR.maxTidOption.get(); ++i) threadCounter[i] = 0;
                                threadPermu = new boolean[RR.maxTidOption.get()][100];
                                setFlag = false;
                            }
                        }
                    }

                    threadPermu[tid] = arrayPermute(100,samplingRate.get().intValue());
                }
            }
            //adaptive sampling case
            else {
                if(setFlag){
                    synchronized (setFlagLock){
                        if(setFlag){
                            burstLength = burstLen.get().intValue();
                            minSamp = minSampling.get().intValue();
                            decRate = decrementRate.get().intValue();

                            if(samplingType.get().intValue()==0){
                                maxSamplingLvl = (burstLength-minSamp)/decRate + 1;
                                adaptivPermu = new boolean[maxSamplingLvl][burstLength];
                                for(int i=0;i<maxSamplingLvl;++i)
                                    adaptivPermu[i] = arrayPermute(burstLength,burstLength-decRate*i);
                                }
                            else{
                                int dummy = burstLength;
                                maxSamplingLvl = 0;
                                while(dummy>=minSamp){
                                    maxSamplingLvl++;
                                    dummy /= decRate;
                                }
                                adaptivPermu = new boolean[maxSamplingLvl][burstLength];
                                dummy = burstLength;
                                for(int i=0;i<maxSamplingLvl;++i) {
                                    adaptivPermu[i] = arrayPermute(burstLength, dummy);
                                    dummy /= decRate;
                                }
                            }
                            --maxSamplingLvl;
                            burstLvl = (burstLevel.get().equals("global")?true:false);
                            setFlag = false;
                        }
                    }
                }
            }

            incEpochAndCV(st, null);
            Util.log("Initial E for " + tid + ": " + Epoch.toString(ts_get_E(st)));
        }

        super.create(event);
    }


    public boolean[] arrayPermute(int len, int tees){

        List<Boolean> dummy = new ArrayList<Boolean>();
        for(int i = 0; i < tees; i++) dummy.add(true);
        for(int i = 0; i < (len-tees); i++) dummy.add(false);
        Collections.shuffle(dummy);
        boolean[] temp = new boolean[len];
        for(int i=0;i<len;++i)
            temp[i] = dummy.get(i).booleanValue();
        return temp;
    }

    @Override
    public void acquire(final AcquireEvent event) {
        final ShadowThread st = event.getThread();
        final FTLockState lockV = getV(event.getLock());

        maxEpochAndCV(st, lockV, event.getInfo());

        super.acquire(event);
        if (COUNT_OPERATIONS)
            acquire.inc(st.getTid());
    }

    @Override
    public void release(final ReleaseEvent event) {
        final ShadowThread st = event.getThread();
        final VectorClock tV = ts_get_V(st);
        final VectorClock lockV = getV(event.getLock());

        lockV.max(tV);
        incEpochAndCV(st, event.getInfo());

        super.release(event);
        if (COUNT_OPERATIONS)
            release.inc(st.getTid());
    }

    
    static FTVarState ts_get_badVarState(ShadowThread st) {
        Assert.panic("Bad");
        return null;
    }

    static void ts_set_badVarState(ShadowThread st, FTVarState v) {
        Assert.panic("Bad");
    }

    protected static ShadowVar getOriginalOrBad(ShadowVar original, ShadowThread st) {
        final FTVarState savedState = ts_get_badVarState(st);
        if (savedState != null) {
            ts_set_badVarState(st, null);
            return savedState;
        } else {
            return original;
        }
    }

    @Override
    public void access(final AccessEvent event) {
        SourceLocation sl = event.getAccessInfo().getLoc();
        int line = sl.getLine();

        int offset = sl.getOffset();
        MethodInfo methInfo = sl.getMethod();
        String methName = methInfo.getName();
        ClassInfo className = methInfo.getOwner();
        String desc = methInfo.getDescriptor();

        final ShadowThread st = event.getThread();
        final ShadowVar shadow = getOriginalOrBad(event.getOriginalShadow(), st);

        if (shadow instanceof FTVarState) {

            FTVarState sx = (FTVarState) shadow;

            Object target = event.getTarget();
            if (target == null) {
                ClassInfo owner = ((FieldAccessEvent) event).getInfo().getField().getOwner();
                final VectorClock tV = ts_get_V(st);
                synchronized (classInitTime) {
                    VectorClock initTime = classInitTime.get(owner);
                    maxEpochAndCV(st, initTime, event.getAccessInfo()); // won't change current
                    // epoch
                }
            }
            // for the checks
            boolean sampleCheck = false;
            int istype = samplingType.get().intValue();
            int tid = st.getTid();
            // normal samplint
            if(samplingScheme.get().equals("count")){
                if(istype==0)
                    sampleCheck = (Math.random()*100 < samplingRate.get()) ? true : false;
                else if(istype==1){
                    synchronized (globalCntrLock){
                        sampleCheck = globalPermu[globalCounter];
                        globalCounter++;
                        if(globalCounter==100)
                            globalCounter = 0;
                    }
                }
                else{
                    sampleCheck = threadPermu[tid][threadCounter[tid]];
                    threadCounter[tid]++;
                    if(threadCounter[tid]==100)
                        threadCounter[tid] = 0;
                }
            }
            // adaptive sampling
            else{
                if(burstLvl){
                    synchronized (sx){
                        sx.globalCntr--;
                        sampleCheck = adaptivPermu[sx.samplingLvl][sx.globalCntr];
                        if(sx.globalCntr==0){
                            sx.globalCntr = burstLength;
                            if(sx.samplingLvl<maxSamplingLvl)
                                sx.samplingLvl++;
                        }
                    }
                }
                else{
                    sx.threadAccessCount[tid]--;
                    sampleCheck =adaptivPermu[sx.threadSamplingLvl[tid]][sx.threadAccessCount[tid]];
                    if(sx.threadAccessCount[tid]==0){
                        sx.threadAccessCount[tid] = burstLength;
                        if(sx.threadSamplingLvl[tid]<maxSamplingLvl)
                            sx.threadSamplingLvl[tid]++;
                    }
                }
            }
            if(sampleCheck) {
                if (event.isWrite()) {
                    write(event, st, sx);
                } else {
                    read(event, st, sx);
                }
            }
        } else {
            super.access(event);
        }
    }

    // Counters for relative frequencies of each rule
    private static final ThreadLocalCounter readSameEpoch = new ThreadLocalCounter("FT",
            "Read Same Epoch", RR.maxTidOption.get());
    private static final ThreadLocalCounter readSharedSameEpoch = new ThreadLocalCounter("FT",
            "ReadShared Same Epoch", RR.maxTidOption.get());
    private static final ThreadLocalCounter readExclusive = new ThreadLocalCounter("FT",
            "Read Exclusive", RR.maxTidOption.get());
    private static final ThreadLocalCounter readShare = new ThreadLocalCounter("FT", "Read Share",
            RR.maxTidOption.get());
    private static final ThreadLocalCounter readShared = new ThreadLocalCounter("FT", "Read Shared",
            RR.maxTidOption.get());
    private static final ThreadLocalCounter writeReadError = new ThreadLocalCounter("FT",
            "Write-Read Error", RR.maxTidOption.get());
    private static final ThreadLocalCounter writeSameEpoch = new ThreadLocalCounter("FT",
            "Write Same Epoch", RR.maxTidOption.get());
    private static final ThreadLocalCounter writeExclusive = new ThreadLocalCounter("FT",
            "Write Exclusive", RR.maxTidOption.get());
    private static final ThreadLocalCounter writeShared = new ThreadLocalCounter("FT",
            "Write Shared", RR.maxTidOption.get());
    private static final ThreadLocalCounter writeWriteError = new ThreadLocalCounter("FT",
            "Write-Write Error", RR.maxTidOption.get());
    private static final ThreadLocalCounter readWriteError = new ThreadLocalCounter("FT",
            "Read-Write Error", RR.maxTidOption.get());
    private static final ThreadLocalCounter sharedWriteError = new ThreadLocalCounter("FT",
            "Shared-Write Error", RR.maxTidOption.get());
    private static final ThreadLocalCounter acquire = new ThreadLocalCounter("FT", "Acquire",
            RR.maxTidOption.get());
    private static final ThreadLocalCounter release = new ThreadLocalCounter("FT", "Release",
            RR.maxTidOption.get());
    private static final ThreadLocalCounter fork = new ThreadLocalCounter("FT", "Fork",
            RR.maxTidOption.get());
    private static final ThreadLocalCounter join = new ThreadLocalCounter("FT", "Join",
            RR.maxTidOption.get());
    private static final ThreadLocalCounter barrier = new ThreadLocalCounter("FT", "Barrier",
            RR.maxTidOption.get());
    private static final ThreadLocalCounter wait = new ThreadLocalCounter("FT", "Wait",
            RR.maxTidOption.get());
    private static final ThreadLocalCounter vol = new ThreadLocalCounter("FT", "Volatile",
            RR.maxTidOption.get());

    private static final ThreadLocalCounter other = new ThreadLocalCounter("FT", "Other",
            RR.maxTidOption.get());

    static {
        AggregateCounter reads = new AggregateCounter("FT", "Total Reads", readSameEpoch,
                readSharedSameEpoch, readExclusive, readShare, readShared, writeReadError);
        AggregateCounter writes = new AggregateCounter("FT", "Total Writes", writeSameEpoch,
                writeExclusive, writeShared, writeWriteError, readWriteError, sharedWriteError);
        AggregateCounter accesses = new AggregateCounter("FT", "Total Access Ops", reads, writes);
        new AggregateCounter("FT", "Total Ops", accesses, acquire, release, fork, join, barrier,
                wait, vol, other);
    }


    protected void read(final AccessEvent event, final ShadowThread st, final FTVarState sx) {
        final int/* epoch */ e = ts_get_E(st);

        /* optional */ {
            final int/* epoch */ r = sx.R;
            if (r == e) {
                if (COUNT_OPERATIONS)
                    readSameEpoch.inc(st.getTid());
                return;
            } else if (r == Epoch.READ_SHARED && sx.get(st.getTid()) == e) {
                if (COUNT_OPERATIONS)
                    readSharedSameEpoch.inc(st.getTid());
                return;
            }
        }

        synchronized (sx) {
            final VectorClock tV = ts_get_V(st);
            final int/* epoch */ r = sx.R;
            final int/* epoch */ w = sx.W;
            final int wTid = Epoch.tid(w);
            final int tid = st.getTid();

            if (wTid != tid && !Epoch.leq(w, tV.get(wTid))) {
                if (COUNT_OPERATIONS)
                    writeReadError.inc(tid);
                error(event, sx, "Write-Read Race", "Write by ", wTid, "Read by ", tid);
                // best effort recovery:
                return;
            }

            if (r != Epoch.READ_SHARED) {
                final int rTid = Epoch.tid(r);
                if (rTid == tid || Epoch.leq(r, tV.get(rTid))) {
                    if (COUNT_OPERATIONS)
                        readExclusive.inc(tid);
                    sx.R = e;
                } else {
                    if (COUNT_OPERATIONS)
                        readShare.inc(tid);
                    int initSize = Math.max(Math.max(rTid, tid), INIT_VECTOR_CLOCK_SIZE);
                    sx.makeCV(initSize);
                    sx.set(rTid, r);
                    sx.set(tid, e);
                    sx.R = Epoch.READ_SHARED;
                }
            } else {
                if (COUNT_OPERATIONS)
                    readShared.inc(tid);
                sx.set(tid, e);
            }
        }
    }


    protected void write(final AccessEvent event, final ShadowThread st, final FTVarState sx) {
        final int/* epoch */ e = ts_get_E(st);

        /* optional */ {
            final int/* epoch */ w = sx.W;
            if (w == e) {
                if (COUNT_OPERATIONS)
                    writeSameEpoch.inc(st.getTid());
                return;
            }
        }

        synchronized (sx) {
            final int/* epoch */ w = sx.W;
            final int wTid = Epoch.tid(w);
            final int tid = st.getTid();
            final VectorClock tV = ts_get_V(st);

            if (wTid != tid /* optimization */ && !Epoch.leq(w, tV.get(wTid))) {
                if (COUNT_OPERATIONS)
                    writeWriteError.inc(tid);
                error(event, sx, "Write-Write Race", "Write by ", wTid, "Write by ", tid);
            }

            final int/* epoch */ r = sx.R;
            if (r != Epoch.READ_SHARED) {
                final int rTid = Epoch.tid(r);
                if (rTid != tid /* optimization */ && !Epoch.leq(r, tV.get(rTid))) {
                    if (COUNT_OPERATIONS)
                        readWriteError.inc(tid);
                    error(event, sx, "Read-Write Race", "Read by ", rTid, "Write by ", tid);
                } else {
                    if (COUNT_OPERATIONS)
                        writeExclusive.inc(tid);
                }
            } else {
                if (sx.anyGt(tV)) {
                    for (int prevReader = sx.nextGt(tV, 0); prevReader > -1; prevReader = sx
                            .nextGt(tV, prevReader + 1)) {
                        error(event, sx, "Read(Shared)-Write Race", "Read by ", prevReader,
                                "Write by ", tid);
                    }
                    if (COUNT_OPERATIONS)
                        sharedWriteError.inc(tid);
                } else {
                    if (COUNT_OPERATIONS)
                        writeShared.inc(tid);
                }
            }
            sx.W = e;
        }
    }


    @Override
    public void volatileAccess(final VolatileAccessEvent event) {
        final ShadowThread st = event.getThread();
        final VectorClock volV = getV((event).getShadowVolatile());

        if (event.isWrite()) {
            final VectorClock tV = ts_get_V(st);
            volV.max(tV);
            incEpochAndCV(st, event.getAccessInfo());
        } else {
            maxEpochAndCV(st, volV, event.getAccessInfo());
        }

        super.volatileAccess(event);
        if (COUNT_OPERATIONS)
            vol.inc(st.getTid());
    }

    // st forked su
    @Override
    public void preStart(final StartEvent event) {
        final ShadowThread st = event.getThread();
        final ShadowThread su = event.getNewThread();
        final VectorClock tV = ts_get_V(st);

        /*
         * Safe to access su.V, because u has not started yet. This will give us exclusive access to
         * it. There may be a race if two or more threads race are starting u, but of course, a
         * second attempt to start u will crash... RR guarantees that the forked thread will
         * synchronize with thread t before it does anything else.
         */
        maxAndIncEpochAndCV(su, tV, event.getInfo());
        incEpochAndCV(st, event.getInfo());

        super.preStart(event);
        if (COUNT_OPERATIONS)
            fork.inc(st.getTid());
    }

    @Override
    public void stop(ShadowThread st) {
        synchronized (maxEpochPerTid) {
            maxEpochPerTid.set(st.getTid(), ts_get_E(st));
        }
        super.stop(st);
        if (COUNT_OPERATIONS)
            other.inc(st.getTid());
    }

    // t joined on u
    @Override
    public void postJoin(final JoinEvent event) {
        final ShadowThread st = event.getThread();
        final ShadowThread su = event.getJoiningThread();

        // move our clock ahead. Safe to access su.V, as above, when
        // lock is held and u is not running. Also, RR guarantees
        // this thread has sync'd with u.

        maxEpochAndCV(st, ts_get_V(su), event.getInfo());
        // no need to inc su's clock here -- that was just for
        // the proof in the original FastTrack rules.

        super.postJoin(event);
        if (COUNT_OPERATIONS)
            join.inc(st.getTid());
    }

    @Override
    public void preWait(WaitEvent event) {
        final ShadowThread st = event.getThread();
        final VectorClock lockV = getV(event.getLock());
        lockV.max(ts_get_V(st)); // we hold lock, so no need to sync here...
        incEpochAndCV(st, event.getInfo());
        super.preWait(event);
        if (COUNT_OPERATIONS)
            wait.inc(st.getTid());
    }

    @Override
    public void postWait(WaitEvent event) {
        final ShadowThread st = event.getThread();
        final VectorClock lockV = getV(event.getLock());
        maxEpochAndCV(st, lockV, event.getInfo()); // we hold lock here
        super.postWait(event);
        if (COUNT_OPERATIONS)
            wait.inc(st.getTid());
    }

    public static String toString(final ShadowThread td) {
        return String.format("[tid=%-2d   C=%s   E=%s]", td.getTid(), ts_get_V(td),
                Epoch.toString(ts_get_E(td)));
    }

    private final Decoration<ShadowThread, VectorClock> vectorClockForBarrierEntry = ShadowThread
            .makeDecoration("FT:barrier", DecorationFactory.Type.MULTIPLE,
                    new NullDefault<ShadowThread, VectorClock>());

    public void preDoBarrier(BarrierEvent<FTBarrierState> event) {
        final ShadowThread st = event.getThread();
        final FTBarrierState barrierObj = event.getBarrier();
        synchronized (barrierObj) {
            final VectorClock barrierV = barrierObj.enterBarrier();
            barrierV.max(ts_get_V(st));
            vectorClockForBarrierEntry.set(st, barrierV);
        }
        if (COUNT_OPERATIONS)
            barrier.inc(st.getTid());
    }

    public void postDoBarrier(BarrierEvent<FTBarrierState> event) {
        final ShadowThread st = event.getThread();
        final FTBarrierState barrierObj = event.getBarrier();
        synchronized (barrierObj) {
            final VectorClock barrierV = vectorClockForBarrierEntry.get(st);
            barrierObj.stopUsingOldVectorClock(barrierV);
            maxAndIncEpochAndCV(st, barrierV, null);
        }
        if (COUNT_OPERATIONS)
            barrier.inc(st.getTid());
    }

    ///

    @Override
    public void classInitialized(ClassInitializedEvent event) {
        final ShadowThread st = event.getThread();
        final VectorClock tV = ts_get_V(st);
        synchronized (classInitTime) {
            VectorClock initTime = classInitTime.get(event.getRRClass());
            initTime.copy(tV);
        }
        incEpochAndCV(st, null);
        super.classInitialized(event);
        if (COUNT_OPERATIONS)
            other.inc(st.getTid());
    }

    @Override
    public void classAccessed(ClassAccessedEvent event) {
        final ShadowThread st = event.getThread();
        synchronized (classInitTime) {
            final VectorClock initTime = classInitTime.get(event.getRRClass());
            maxEpochAndCV(st, initTime, null);
        }
        if (COUNT_OPERATIONS)
            other.inc(st.getTid());
    }

    @Override
    public void printXML(XMLWriter xml) {
        for (ShadowThread td : ShadowThread.getThreads()) {
            xml.print("thread", toString(td));
        }
    }

    protected void error(final AccessEvent ae, final FTVarState x, final String description,
                         final String prevOp, final int prevTid, final String curOp, final int curTid) {

        if (ae instanceof FieldAccessEvent) {
            fieldError((FieldAccessEvent) ae, x, description, prevOp, prevTid, curOp, curTid);
        } else {
            arrayError((ArrayAccessEvent) ae, x, description, prevOp, prevTid, curOp, curTid);
            arrayError((ArrayAccessEvent) ae, x, description, prevOp, prevTid, curOp, curTid);
        }

    }

    protected void arrayError(final ArrayAccessEvent aae, final FTVarState sx,
                              final String description, final String prevOp, final int prevTid, final String curOp,
                              final int curTid) {
        final ShadowThread st = aae.getThread();
        final Object target = aae.getTarget();

        if (arrayErrors.stillLooking(aae.getInfo())) {
            arrayErrors.error(st, aae.getInfo(), "Alloc Site", ArrayAllocSiteTracker.get(target),
                    "Shadow State", sx, "Current Thread", toString(st), "Array",
                    Util.objectToIdentityString(target) + "[" + aae.getIndex() + "]", "Message",
                    description, "Previous Op", prevOp + " " + ShadowThread.get(prevTid),
                    "Currrent Op", curOp + " " + ShadowThread.get(curTid), "Stack",
                    ShadowThread.stackDumpForErrorMessage(st));
        }
        Assert.assertTrue(prevTid != curTid);

        aae.getArrayState().specialize();

        if (!arrayErrors.stillLooking(aae.getInfo())) {
            advance(aae);
        }
    }

    protected void fieldError(final FieldAccessEvent fae, final FTVarState sx,
                              final String description, final String prevOp, final int prevTid, final String curOp,
                              final int curTid) {
        final FieldInfo fd = fae.getInfo().getField();
        final ShadowThread st = fae.getThread();
        final Object target = fae.getTarget();

        if (fieldErrors.stillLooking(fd)) {
            fieldErrors.error(st, fd, "Shadow State", sx, "Current Thread", toString(st), "Class",
                    (target == null ? fd.getOwner() : target.getClass()), "Field",
                    Util.objectToIdentityString(target) + "." + fd, "Message", description,
                    "Previous Op", prevOp + " " + ShadowThread.get(prevTid), "Currrent Op",
                    curOp + " " + ShadowThread.get(curTid), "Stack",
                    ShadowThread.stackDumpForErrorMessage(st));
        }

        Assert.assertTrue(prevTid != curTid);

        if (!fieldErrors.stillLooking(fd)) {
            advance(fae);
        }
    }
}
