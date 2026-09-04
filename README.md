# APKeep

APKeep is a data plane verification tool that checks network invariants for network updates.
The work is published in the [NSDI'20 paper](https://www.usenix.org/conference/nsdi20/presentation/zhang-peng) "APKeep: Realtime Verification for Real Networks".
This branch provides a prototype implementation of APKeep.

## How to run APKeep

APKeep is a `Java` project and can be easily built by `Maven`.
This branch is developed and tested under JDK 11 and Maven v3.9.6. 

### setup dataset

APKeep analyzes the network by taking input from several files in specific formats.
Read [networks](networks/) to know the requirements of the input files.
Make sure you prepare the necessary files before running APKeep.

### run APKeep

To build APKeep, simply run:

```bash
mvn package
```

Then you can invoke APKeep in CLI:

```bash
java -jar target/apkeep-1.0.0.jar
```

### non-interactive incremental and Burst runs

The shaded JAR also supports reproducible, non-interactive runs. Incremental
mode applies every update and checks both forwarding loops and blackholes after
each update:

```bash
java -jar target/apkeep-1.0.0.jar \
  -incr /absolute/path/to/dataset
```

Burst mode applies the complete update stream without intermediate checking,
then performs one of two final checks. Both the historical `-brust` spelling
and the correctly spelled `-burst` alias are accepted:

```bash
java -jar target/apkeep-1.0.0.jar \
  -burst /absolute/path/to/dataset --verify invariants

java -jar target/apkeep-1.0.0.jar \
  -brust /absolute/path/to/dataset --verify reachability
```

Use `--output /absolute/path/to/new-or-empty-directory` to choose the result
directory. Otherwise results are written below
`results/<dataset>/<timestamp>-<mode>/`. A non-empty output directory is never
overwritten.

Each command performs one complete warmup followed by three measured trials.
Every trial starts with a fresh empty APKeep model and uses the same input that
was loaded into memory before the warmup. A failed warmup aborts the command. A
failed measured trial is recorded, and the remaining measured trials still run
from fresh models. The process exits non-zero if a warmup or measured trial
fails.

#### parameters.json is optional

When `parameters.json` is absent, these defaults are used (with `NAME` set to
the dataset directory name):

```json
{
  "NAME": "<dataset-directory-name>",
  "MergeAP": true,
  "BDD_TABLE_SIZE": 100000000,
  "GC_INTERVAL": 100000,
  "TOTAL_AP_THRESHOLD": 500,
  "LOW_MERGEABLE_AP_THRESHOLD": 10,
  "HIGH_MERGEABLE_AP_THRESHOLD": 50,
  "WRITE_RESULT_INTERVAL": 1,
  "PRINT_RESULT_INTERVAL": 100000,
  "FAST_UPDATE_THRESHOLD": 0.25
}
```

A present file may contain only the fields it needs to override. An absent or
blank `NAME` still falls back to the directory name. Parameters, including
`MergeAP`, are reset before every trial so one trial cannot affect another.

The non-interactive modes require `topo.txt` and `updates`. `devices.txt`,
`vlan.txt`, `acls/`, and APKeep's native `nat.txt` remain optional. A dataset
containing the MINT-style `nat_updates` file is rejected explicitly; native
APKeep NAT declarations and native NAT update rows remain supported.

#### invariant semantics

Incremental checking stops at the first loop or blackhole caused by each
update, then performs the normal soft merge and continues with the next update.
Burst invariant checking covers every final forwarding atomic predicate from
every real forwarding-device ingress. It stops at the first violation for each
`ingress × AP` unit but continues with the remaining units.

A blackhole is non-empty traffic assigned to the `default` port of a real
forwarding element. ACL deny, `self`, an output port with no internal topology
successor, and terminal nodes are normal termination. The default behavior of
a native NAT element is identity, not a blackhole.

#### reachability workload

`--verify reachability` requires an existing `reachability.txt`; APKeep does
not generate it. Comments beginning with `#` and blank lines are ignored. Each
query has six whitespace-separated fields:

```text
id network prefixLength source destination expected
1 167772160 24 r1 r2 true
```

IDs must be consecutive from 1, `network` is an unsigned decimal IPv4 network
address aligned to a prefix of length 0–32, source and destination must be
different forwarding devices, and expected is exactly `true` or `false`.
Reachability uses EXISTS semantics: a query is true when at least one packet in
the prefix reaches the destination device. Arrival at the destination counts
as success before applying its forwarding rules. VLAN and multiple topology
successors are existential branches; visited AP state guarantees termination
in forwarding loops. Expected mismatches are reported as experimental results
and do not fail a trial.

#### result files and measurement boundaries

The output directory contains:

```text
trials.csv
summary.csv
incremental-samples.csv.gz   # incremental mode only
run.properties
```

The CSV layout follows the MINT experiment result schema. Standalone runs use
`experiment=0`, `method=apkeep`, `rule_profile=FULL`, and one of
`INCREMENTAL_INVARIANTS`, `BURST_INVARIANTS`, or `BURST_REACHABILITY`. BDD
migration is always zero for APKeep. `summary.csv` includes
`count/min/mean/p50/p90/p95/p99/max/stddev`; percentiles use linear
interpolation and standard deviation is the population value.

`model_ns` includes rule encoding, hit/AP updates, soft merges, and the final
hard merge. `model_finalize_ns` identifies the final hard-merge part and is
already included in `model_ns`. `verification_ns` contains only property or
reachability checking. Incremental samples preserve the separate model and
verification time of every in-memory update.

`identify_changes_ns` measures only the call to `identifyChangesInsert()` or
`identifyChangesRemove()` inside the rule-table hit cascade. It is a subset of
`model_ns`, not an additional component of `total_ns`. Trie/list lookup, rule
encoding, `updatePortPredicateMap()`, AP merging, and verification are outside
this sub-timer. Updates that return before invoking identify logic—such as a
duplicate insertion, a missing deletion, or deletion of a hidden rule—record
zero. `trials.csv` and `summary.csv` contain the trial aggregate for both
Incremental and Burst. `incremental-samples.csv.gz` additionally records the
per-update value; Burst intentionally has no per-update sample file.

Heap usage deliberately measures retained model growth rather than validation
peak memory. The input and primitive timing arrays are allocated first, a
stable-GC heap baseline is read, the model is built, verification temporaries
are cleared, and a second stable-GC value is read while the model remains
alive. For Burst, the second reading occurs before final verification.

APKeep provides several commands to analyze a network.
First, initialize the network snapshot by specifying the folder that contains the required files, for example:

```bash
APKeep>init ../networks/stanford
APKeep>
```

Then, invoke the verification by specifying the file that contains the rule updates.
You can also omit the parameter if the file is in the same folder as the initial snapshot, for example:

```bash
APKeep>update
The stanford dataset
Number of updates: 9052
Total time: 865ms
Update PPM time: 605ms
Check property time: 259ms
Number of APs after insert: 515
Number of APs after update: 2
Number of loops: 20
Average update time: 95.564us
95.0287229341582% < 0.25ms
Memory Usage: 0MB
APKeep>
```

Finally, you can dump loops (if any) or run link failure tests to check the "what if" question, details can be found in the APKeep paper.

```bash
APKeep>dump loops
++++++++++++++++++++++++++++++
loop found for [171.66.255.128/26]:
bbra_rtr,te7/1 bbrb_rtr,te7/1 bbrb_rtr,te6/3 yozb_rtr,te1/1 yozb_rtr,te1/2 yoza_rtr,te1/2 yoza_rtr,te7/1 bbrb_rtr,te7/4 bbrb_rtr,te7/2 cozb_rtr,te2/1 cozb_rtr,te3/1 cozb_rtr_outACL_te3/1_out,inport cozb_rtr_outACL_te3/1_out,permit bbra_rtr,te6/1 bbra_rtr,te7/1
++++++++++++++++++++++++++++++
APKeep>
```

## How to develop using APKeep

The source code of APKeep is in [src/main/java/](src/main/java/), which consists of three modules:
> - `apkeep` is the main module that maintains PPM and verifier;
> - `common` is imported from [AP Transformer](https://www.cs.utexas.edu/users/lam/NRL/), which wraps BDD operation on network packets;
> - `JDD` is imported as a Maven dependency, which is an [open-source](https://bitbucket.org/vahidi/jdd/) BDD library for Java.

To develop your own data plane verifier using APKeep, you might use or modify part of the source files.

### package core

- **APKeeper** manages PPM, including the data structures of `port_aps` and `ap_ports`, as well as the algorithms updating PPM, such as `Split`, `Transfer`, and `Merge`.
- **Network** manages `Element`s for network devices, also provides APIs to interact with input files.
- **ChangeItem** defines the behavior change in the form of 3-tuple.

### package element

- **Element** manages `aps` for each `port`, including the algorithm `EncodingRules`, `IdentifyChanges`, and `UpdatingPredicates`.
- **ForwardElement** inherits Element and optimizes updating algorithms for IP forwarding rule using prefix trie tree.
- **ACLElement** inherits Element and works on a prioritized acl rule list.
- **NATElement** inherits Element and overwrites algorithms for updating `rewrite table`.

### package checker
- **Loop** defines the forwarding loop and records the relevant packets.
- **ForwardingGraph** defines the forwarding graph for a set of `AP`s, including the nodes and ports holding such `ap`.
- **Checker** implements the algorithms to check invariants, including `ConstructForwardingGraph`, `TraverseForwardingGraph`, and directly `TraversePPM` without constructing a forwarding graph.

### the others

The other packages define some useful data structures during verification, please check the code for details.

## For Researchers

To evaluate APKeep using the experiments from the NSDI paper, we provide [ExampleExp.java](src/main/java/apkeep/main/main.java).
You can find part of the datasets in [networks](networks/).

## Support

Feel free to contact us if issues occur to you.

- Peng Zhang (p-zhang@xjtu.edu.cn)
- Xu Liu (x.liu.reason@outlook.com)
- Ning Kang (kangning2018@foxmail.com)

## License
APKeep is released under [license](LICENSE).
