-------------------------- MODULE CommitRecovery --------------------------
EXTENDS Naturals, Sequences, FiniteSets

CONSTANTS MaxCommits, MaxInputs, MaxCrashes, SyncReadback, SyncRecovery
VARIABLES pc, log, stable, acknowledged, snapshot, pending, staged,
          nextInput, inFlight, memory, crashes

vars == <<pc, log, stable, acknowledged, snapshot, pending, staged,
          nextInput, inFlight, memory, crashes>>
Consumed(s) == {s[i] : i \in 1..Len(s)} \ {0}
Prefix(s, n) == SubSeq(s, 1, n)

Init == /\ pc = "idle"
        /\ log = <<>> /\ stable = <<>>
        /\ acknowledged = 0 /\ snapshot = 0 /\ memory = 0
        /\ pending = 0 /\ staged = {} /\ nextInput = 1 /\ inFlight = 0
        /\ crashes = 0

Stage == /\ pc = "idle" /\ pending = 0 /\ nextInput <= MaxInputs
         /\ pending' = nextInput /\ staged' = staged \cup {nextInput}
         /\ nextInput' = nextInput + 1
         /\ UNCHANGED <<pc, log, stable, acknowledged, snapshot, inFlight, memory, crashes>>

Begin == /\ pc = "idle" /\ Len(log) < MaxCommits
         /\ inFlight' = pending /\ pc' = "writing"
         /\ UNCHANGED <<log, stable, acknowledged, snapshot, pending, staged, nextInput, memory, crashes>>

Write == /\ pc = "writing"
         /\ log' = Append(log, inFlight) /\ pc' = "syncing"
         /\ UNCHANGED <<stable, acknowledged, snapshot, pending, staged, nextInput, inFlight, memory, crashes>>

Sync == /\ pc = "syncing" /\ stable' = log /\ pc' = "acknowledging"
        /\ UNCHANGED <<log, acknowledged, snapshot, pending, staged, nextInput, inFlight, memory, crashes>>

\* A write/sync exception can leave a complete, authenticated but volatile frame.
AppendFailure == /\ pc = "syncing" /\ pc' = "readback"
                 /\ UNCHANGED <<log, stable, acknowledged, snapshot, pending, staged, nextInput, inFlight, memory, crashes>>
Readback == /\ pc = "readback" /\ pc' = "acknowledging"
            /\ stable' = IF SyncReadback THEN log ELSE stable
            /\ UNCHANGED <<log, acknowledged, snapshot, pending, staged, nextInput, inFlight, memory, crashes>>

Ack == /\ pc = "acknowledging" /\ acknowledged' = Len(log)
       /\ memory' = Len(log) /\ pc' = "checkpointing"
       /\ UNCHANGED <<log, stable, snapshot, pending, staged, nextInput, inFlight, crashes>>

\* Cache writes may fail; neither failure nor cleanup can revoke an acknowledgement.
Checkpoint == /\ pc = "checkpointing" /\ snapshot' \in {snapshot, memory}
              /\ pc' = "retiring"
              /\ UNCHANGED <<log, stable, acknowledged, pending, staged, nextInput, inFlight, memory, crashes>>
Retire == /\ pc = "retiring" /\ pending' = 0 /\ pc' = "idle"
          /\ UNCHANGED <<log, stable, acknowledged, snapshot, staged, nextInput, inFlight, memory, crashes>>
CleanupFailure == /\ pc = "retiring" /\ pc' = "recovering"
                  /\ UNCHANGED <<log, stable, acknowledged, snapshot, pending, staged, nextInput, inFlight, memory, crashes>>

\* Process death preserves kernel-visible bytes; power loss may retain any complete
\* prefix between the fsynced prefix and visible tail. Torn tails abstract to no commit.
ProcessDeath == /\ pc # "recovering" /\ crashes < MaxCrashes
                /\ pc' = "recovering" /\ memory' = 0 /\ crashes' = crashes + 1
                /\ UNCHANGED <<log, stable, acknowledged, snapshot, pending, staged, nextInput, inFlight>>
PowerLoss == /\ pc # "recovering" /\ crashes < MaxCrashes
             /\ \E n \in Len(stable)..Len(log):
                    /\ log' = Prefix(log, n) /\ stable' = Prefix(log, n)
             /\ pc' = "recovering" /\ memory' = 0 /\ crashes' = crashes + 1
             /\ UNCHANGED <<acknowledged, snapshot, pending, staged, nextInput, inFlight>>

Recover == /\ pc = "recovering"
           /\ stable' = IF SyncRecovery THEN log ELSE stable
           /\ memory' = Len(log) /\ snapshot' = Len(log)
           /\ pending' = IF pending \in Consumed(log) THEN 0 ELSE pending
           /\ pc' = "idle"
           /\ UNCHANGED <<log, acknowledged, staged, nextInput, inFlight, crashes>>

Next == Stage \/ Begin \/ Write \/ Sync \/ AppendFailure \/ Readback \/ Ack
        \/ Checkpoint \/ Retire \/ CleanupFailure \/ ProcessDeath \/ PowerLoss \/ Recover
Spec == Init /\ [][Next]_vars

TypeOK == /\ pc \in {"idle", "writing", "syncing", "readback", "acknowledging",
                     "checkpointing", "retiring", "recovering"}
          /\ log \in Seq(0..MaxInputs) /\ stable \in Seq(0..MaxInputs)
          /\ Len(log) <= MaxCommits /\ stable = Prefix(log, Len(stable))
          /\ acknowledged \in 0..MaxCommits /\ snapshot \in 0..MaxCommits
          /\ memory \in 0..MaxCommits /\ pending \in 0..MaxInputs
          /\ staged \subseteq 1..MaxInputs /\ nextInput \in 1..(MaxInputs + 1)
          /\ inFlight \in 0..MaxInputs /\ crashes \in 0..MaxCrashes
AcknowledgedDurable == acknowledged <= Len(stable)
SnapshotBackedByDurableLog == snapshot <= Len(stable)
PendingAccountedFor == staged \subseteq Consumed(stable) \cup ({pending} \ {0})
ConsumedAtMostOnce == \A id \in 1..MaxInputs:
                       Cardinality({i \in 1..Len(log): log[i] = id}) <= 1
RecoveredProjection == pc = "idle" => memory = Len(stable)
=============================================================================
