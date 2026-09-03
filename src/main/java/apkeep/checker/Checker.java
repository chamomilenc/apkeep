package apkeep.checker;

import java.util.ArrayList;
import java.util.ArrayDeque;
import java.util.Collections;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import apkeep.core.Network;
import apkeep.elements.ACLElement;
import apkeep.elements.Element;
import apkeep.elements.ForwardElement;
import common.BDDACLWrapper;
import common.PositionTuple;

public class Checker {
	
	Network net;
	Set<Loop> loops;
	
	public Checker(Network net) {
		this.net = net;
		loops = new HashSet<>();
	}
	
	public Set<Loop> getLoops() {
		return loops;
	}
	
	public ForwardingGraph constructFowardingGraph(PositionTuple pt1) {
		Map<PositionTuple, Set<Integer>> port_aps = new HashMap<>();
		Map<String, Set<PositionTuple>> node_ports = new HashMap<>();
		
		Element e = getElement(pt1.getDeviceName());
		Set<Integer> aps = e.getPortAPs(pt1.getPortName());
		
		if (aps == null) return null;
			
		for (int ap : aps) {
			Set<PositionTuple> pts = null;
			try {
				pts = net.getHoldPorts(ap);
			} catch (Exception e1) {
				e1.printStackTrace();
			}
			for (PositionTuple pt: pts) {
				port_aps.putIfAbsent(pt, new HashSet<>());
				port_aps.get(pt).add(ap);
				
				node_ports.putIfAbsent(pt.getDeviceName(), new HashSet<>());
				node_ports.get(pt.getDeviceName()).add(pt);
			}
		}
		
		return new ForwardingGraph(port_aps, node_ports);
	}
	
	public int checkProperty(ForwardingGraph g) {
		loops.clear();
		
		for(PositionTuple pt : g.port_aps.keySet()) {
			Set<Integer> aps = new HashSet<Integer>(g.port_aps.get(pt));
			ArrayList<PositionTuple> history = new ArrayList<PositionTuple>();
			traverseFowardingGraph(pt, aps, history, g);
		}
		
		return loops.size();
	}
	
	private void traverseFowardingGraph(PositionTuple cur_hop, Set<Integer> fwd_aps, 
			ArrayList<PositionTuple> history,
			ForwardingGraph g) {
		if(fwd_aps.isEmpty()) return;
		/*
		 * check loops
		 */
		if(checkLoop(history, cur_hop, fwd_aps, null)) return;
		history.add(cur_hop);
		
		/*
		 * look up l1-topology for connected node
		 */
		if(net.getConnectedPorts(cur_hop) == null) return;
		for(PositionTuple connected_pt : net.getConnectedPorts(cur_hop)) {
			String next_node = connected_pt.getDeviceName();
			if(!g.node_ports.containsKey(next_node)) continue;
			for(PositionTuple next_hop : g.node_ports.get(next_node)) {
				if(next_hop.equals(connected_pt)) continue;
				Set<Integer> aps = new HashSet<>(g.port_aps.get(next_hop));
				aps.retainAll(fwd_aps);
				ArrayList<PositionTuple> new_history = new ArrayList<>(history);
				new_history.add(connected_pt);
				traverseFowardingGraph(next_hop, aps, new_history, g);
			}
		}
	}

	public void checkProperty(String element_name, Set<Integer> moved_aps) {
		loops.clear();
		
		Element e = net.getElement(element_name);
		for(String port : e.getPorts()) {
			if (port.equals("default") || e.getPortAPs(port).isEmpty()) continue;
			
			Set<Integer> aps = new HashSet<>(moved_aps);
			aps.retainAll(e.getPortAPs(port));
			
			if(aps.isEmpty()) continue;
			Set<String> ports = getPhysicalPorts(e,port);
			for(String next_port : ports) {
				PositionTuple next_hop = new PositionTuple(element_name, next_port);
				ArrayList<PositionTuple> history = new ArrayList<>();
				traversePPM(next_hop, aps, history);
			}
		}
	}
	
	public void checkPropertyDivision(String element_name, Set<Integer> moved_aps) {
		loops.clear();
		
		boolean isACL = false;
		if(net.getElement(element_name) instanceof ACLElement) {
			if(net.getConnectedPorts(new PositionTuple(element_name, "permit"))==null) return;
			isACL = true;
		}
		
		element_name = net.getForwardElement(element_name);
		Element e = net.getElement(element_name);
		
		for(String port : e.getPorts()) {
			if (port.equals("default") || e.getPortAPs(port).isEmpty()) continue;
			
			Set<Integer> fwd_aps = new HashSet<>();
			Set<Integer> acl_aps = new HashSet<>();
			if(isACL) {
				fwd_aps.addAll(e.getPortAPs(port));
				acl_aps.addAll(moved_aps);
			}
			else {
				fwd_aps.addAll(moved_aps);
				fwd_aps.retainAll(e.getPortAPs(port));
				acl_aps.add(BDDACLWrapper.BDDTrue);
			}
			
			if(fwd_aps.isEmpty() || acl_aps.isEmpty()) continue;
			Set<String> ports = getPhysicalPorts(e,port);
			for(String next_port : ports) {
				PositionTuple next_hop = new PositionTuple(element_name, next_port);
				ArrayList<PositionTuple> history = new ArrayList<>();
				traversePPMDivision(next_hop, fwd_aps, acl_aps, history);
			}
		}
	}

	private void traversePPM(PositionTuple cur_hop, Set<Integer> fwd_aps, 
			List<PositionTuple> history) {
		
		if(fwd_aps.isEmpty()) return;
		/*
		 * check loops
		 */
		if(checkLoop(history, cur_hop, fwd_aps, null)) return;
		history.add(cur_hop);
		
		/*
		 * look up l1-topology for connected node
		 */
		if(net.getConnectedPorts(cur_hop) == null) return;
		for(PositionTuple connected_pt : net.getConnectedPorts(cur_hop)) {
			String next_node = connected_pt.getDeviceName();
			Element e = getElement(next_node);
			for(String port : e.getPorts()) {
				if(port.equals(connected_pt.getPortName())) continue;
				Set<Integer> aps = e.forwardAPs(port, fwd_aps);
				Set<String> ports = getPhysicalPorts(e,port);
				for(String next_port : ports) {
					if(next_port.equals(connected_pt.getPortName())) continue;
					PositionTuple next_hop = new PositionTuple(next_node, next_port);
					ArrayList<PositionTuple> new_history = new ArrayList<>(history);
					new_history.add(connected_pt);
					traversePPM(next_hop, aps, new_history);
				}
			}
		}
	}
	
	private void traversePPMDivision(PositionTuple cur_hop, 
			Set<Integer> fwd_aps, Set<Integer> acl_aps,
			List<PositionTuple> history) {
		if(fwd_aps.isEmpty() || acl_aps.isEmpty()) return;
		if(cur_hop.getPortName().equals("deny")) return;
		
		/*
		 * check loops
		 */
		if(checkLoop(history, cur_hop, fwd_aps, acl_aps)) return;
		history.add(cur_hop);
		
		/*
		 * look up l1-topology for connected node
		 */
		if(net.getConnectedPorts(cur_hop) == null) return;
		for(PositionTuple connected_pt : net.getConnectedPorts(cur_hop)) {
			String next_node = connected_pt.getDeviceName();
			Element e = getElement(next_node);
			Set<Integer> filtered_fwd_aps = new HashSet<>(fwd_aps);
			Set<Integer> filtered_acl_aps = new HashSet<>(acl_aps);
			for(String port : e.getPorts()) {
				if(port.equals(connected_pt.getPortName())) continue;
				if(e instanceof ACLElement) {
					filtered_acl_aps = e.forwardAPs(port, acl_aps);
				}
				else {
					filtered_fwd_aps = e.forwardAPs(port, fwd_aps);
				}
				Set<String> ports = getPhysicalPorts(e,port);
				for(String next_port : ports) {
					if(next_port.equals(connected_pt.getPortName())) continue;
					PositionTuple next_hop = new PositionTuple(next_node, next_port);
					ArrayList<PositionTuple> new_history = new ArrayList<>(history);
					new_history.add(connected_pt);
					traversePPMDivision(next_hop, filtered_fwd_aps, filtered_acl_aps, new_history);
				}
			}
		}
	}

	private boolean checkLoop(List<PositionTuple> history, PositionTuple cur_hop,
			Set<Integer> fwd_aps, Set<Integer> acl_aps) {
		if(history.contains(cur_hop)) {
			if(acl_aps != null) {
				if(!Element.hasOverlap(fwd_aps, acl_aps)) {
					return true;
				}
			}
			history.add(cur_hop);
			Loop loop = new Loop(fwd_aps, history, cur_hop);
			loops.add(loop);
			return true;
		}
		return false;
	}
	
	private Element getElement(String node_name) {
		if(net.isACLNode(node_name)) return net.getACLElement(node_name);
		return net.getElement(node_name);
	}
	
	private Set<String> getPhysicalPorts(Element e, String port){
		if(e instanceof ForwardElement) {
			if(port.toLowerCase().startsWith("vlan")) {
				return ((ForwardElement) e).getVlanPorts(port);			
			}
		}
		Set<String> ports = new HashSet<>();
		ports.add(port);
		return ports;
	}

	/**
	 * Verify the behavior changed by one update.  Unlike the historical entry
	 * point, this checks both loops and forwarding-device default blackholes and
	 * returns immediately after the first violation.
	 */
	public VerificationResult verifyUpdate(String elementName, Set<Integer> movedAps) {
		Element changed = net.getElement(elementName);
		if (changed == null || movedAps == null || movedAps.isEmpty()) {
			return VerificationResult.none();
		}
		List<Integer> moved = sortedIntegers(movedAps);
		if (changed instanceof ACLElement) {
			List<String> applications = new ArrayList<String>(net.getAclApplicationNodes(elementName));
			Collections.sort(applications);
			for (String root : applications) {
				for (int aclAp : moved) {
					for (int fwdAp : sortedIntegers(net.getForwardingAtomicPredicates())) {
						if (!overlaps(fwdAp, aclAp)) continue;
						VerificationResult result = verifyOne(root, fwdAp, aclAp);
						if (result.isViolation()) return result;
					}
				}
			}
			return VerificationResult.none();
		}
		for (int fwdAp : moved) {
			VerificationResult result = verifyOne(elementName, fwdAp, BDDACLWrapper.BDDTrue);
			if (result.isViolation()) return result;
		}
		return VerificationResult.none();
	}

	/** Verify every final forwarding AP from every real forwarding ingress. */
	public FullInvariantReport verifyAllForwardingAtomicPredicates() {
		long checked = 0;
		long loopCount = 0;
		long blackholeCount = 0;
		List<String> devices = new ArrayList<String>(net.getForwardingElementNames());
		Collections.sort(devices);
		List<Integer> aps = sortedIntegers(net.getForwardingAtomicPredicates());
		for (String device : devices) {
			for (int ap : aps) {
				checked++;
				VerificationResult result = verifyOne(device, ap, BDDACLWrapper.BDDTrue);
				if (result.getType() == ViolationType.LOOP) loopCount++;
				else if (result.getType() == ViolationType.BLACKHOLE) blackholeCount++;
			}
		}
		return new FullInvariantReport(checked, loopCount, blackholeCount);
	}

	/** Return all forwarding devices reached by any packet in the prefix. */
	public Set<String> reachableDevices(long network, int prefixLength, String source) {
		Element sourceElement = net.getElement(source);
		if (!(sourceElement instanceof ForwardElement)) {
			throw new IllegalArgumentException("unknown forwarding source device: " + source);
		}
		int prefix = net.encodeDestinationPrefix(network, prefixLength);
		List<Integer> aps = sortedIntegers(net.getForwardingAtomicPredicates(prefix));
		Set<State> visited = new HashSet<State>();
		Deque<State> pending = new ArrayDeque<State>();
		for (int ap : aps) pending.addLast(new State(source, null, ap, BDDACLWrapper.BDDTrue));
		Set<String> reachable = new LinkedHashSet<String>();
		while (!pending.isEmpty()) {
			State state = pending.removeFirst();
			if (!visited.add(state) || !stateHasPackets(state)) continue;
			Element direct = net.getElement(state.node);
			if (direct instanceof ForwardElement) reachable.add(state.node);
			Expansion expansion = expand(state, false);
			for (State next : expansion.next) pending.addLast(next);
		}
		return reachable;
	}

	public void clearTransientState() {
		// New verification uses operation-local state only.  The method makes the
		// lifecycle explicit for memory measurements and future checker caches.
	}

	private VerificationResult verifyOne(String root, int fwdAp, int aclAp) {
		Map<State, VisitState> colors = new HashMap<State, VisitState>();
		List<String> path = new ArrayList<String>();
		return dfs(new State(root, null, fwdAp, aclAp), colors, path);
	}

	private VerificationResult dfs(State state, Map<State, VisitState> colors, List<String> path) {
		if (!stateHasPackets(state)) return VerificationResult.none();
		VisitState color = colors.get(state);
		if (color == VisitState.VISITING) {
			List<String> loopPath = new ArrayList<String>(path);
			loopPath.add(state.label());
			return VerificationResult.violation(ViolationType.LOOP, state.fwdAp, loopPath);
		}
		if (color == VisitState.DONE) return VerificationResult.none();
		colors.put(state, VisitState.VISITING);
		path.add(state.label());
		Expansion expansion = expand(state, true);
		if (expansion.blackhole) {
			VerificationResult result = VerificationResult.violation(
					ViolationType.BLACKHOLE, state.fwdAp, path);
			path.remove(path.size() - 1);
			colors.put(state, VisitState.DONE);
			return result;
		}
		for (State next : expansion.next) {
			VerificationResult result = dfs(next, colors, path);
			if (result.isViolation()) {
				path.remove(path.size() - 1);
				return result;
			}
		}
		path.remove(path.size() - 1);
		colors.put(state, VisitState.DONE);
		return VerificationResult.none();
	}

	private Expansion expand(State state, boolean detectBlackhole) {
		Element element = net.resolveTopologyElement(state.node);
		if (element == null) return Expansion.empty();
		if (element instanceof ForwardElement && detectBlackhole) {
			Set<Integer> defaults = element.getPortAPs("default");
			if (defaults != null && defaults.contains(state.fwdAp)) {
				return Expansion.blackhole();
			}
		}

		List<State> next = new ArrayList<State>();
		List<String> ports = new ArrayList<String>(element.getPorts());
		Collections.sort(ports);
		for (String port : ports) {
			if ("default".equals(port)) continue;
			if (state.inputPort != null && state.inputPort.equals(port)) continue;
			if (element instanceof ACLElement && "deny".equals(port)) continue;
			if ("self".equalsIgnoreCase(port)) continue;

			Set<Integer> outputFwd = Collections.singleton(state.fwdAp);
			Set<Integer> outputAcl = Collections.singleton(state.aclAp);
			if (element instanceof ACLElement) {
				outputAcl = element.forwardAPs(port, outputAcl);
			} else {
				outputFwd = element.forwardAPs(port, outputFwd);
			}
			if (outputFwd.isEmpty() || outputAcl.isEmpty()) continue;

			List<String> physicalPorts = new ArrayList<String>(getPhysicalPorts(element, port));
			Collections.sort(physicalPorts);
			for (String physicalPort : physicalPorts) {
				if ("self".equalsIgnoreCase(physicalPort)) continue;
				PositionTuple output = new PositionTuple(state.node, physicalPort);
				Set<PositionTuple> connected = net.getConnectedPorts(output);
				if (connected == null || connected.isEmpty()) continue; // external/terminal
				List<PositionTuple> targets = new ArrayList<PositionTuple>(connected);
				Collections.sort(targets, POSITION_ORDER);
				for (PositionTuple target : targets) {
					for (int fwd : sortedIntegers(outputFwd)) {
						for (int acl : sortedIntegers(outputAcl)) {
							if (overlaps(fwd, acl)) {
								next.add(new State(target.getDeviceName(), target.getPortName(), fwd, acl));
							}
						}
					}
				}
			}
		}
		return new Expansion(false, next);
	}

	private boolean stateHasPackets(State state) {
		return state.fwdAp != BDDACLWrapper.BDDFalse
				&& state.aclAp != BDDACLWrapper.BDDFalse
				&& overlaps(state.fwdAp, state.aclAp);
	}

	private boolean overlaps(int fwdAp, int aclAp) {
		if (!net.isDivisionActivated() || aclAp == BDDACLWrapper.BDDTrue) return true;
		return Element.hasOverlap(Collections.singleton(fwdAp), Collections.singleton(aclAp));
	}

	private static List<Integer> sortedIntegers(Set<Integer> values) {
		List<Integer> result = new ArrayList<Integer>(values);
		Collections.sort(result);
		return result;
	}

	private enum VisitState { VISITING, DONE }

	private static final Comparator<PositionTuple> POSITION_ORDER = new Comparator<PositionTuple>() {
		@Override
		public int compare(PositionTuple left, PositionTuple right) {
			int device = left.getDeviceName().compareTo(right.getDeviceName());
			return device != 0 ? device : left.getPortName().compareTo(right.getPortName());
		}
	};

	private static final class State {
		final String node;
		final String inputPort;
		final int fwdAp;
		final int aclAp;

		State(String node, String inputPort, int fwdAp, int aclAp) {
			this.node = node;
			this.inputPort = inputPort;
			this.fwdAp = fwdAp;
			this.aclAp = aclAp;
		}

		String label() {
			return node + (inputPort == null ? "" : "," + inputPort);
		}

		@Override
		public boolean equals(Object object) {
			if (!(object instanceof State)) return false;
			State other = (State) object;
			return fwdAp == other.fwdAp && aclAp == other.aclAp
					&& node.equals(other.node)
					&& (inputPort == null ? other.inputPort == null : inputPort.equals(other.inputPort));
		}

		@Override
		public int hashCode() {
			int result = node.hashCode();
			result = 31 * result + (inputPort == null ? 0 : inputPort.hashCode());
			result = 31 * result + fwdAp;
			result = 31 * result + aclAp;
			return result;
		}
	}

	private static final class Expansion {
		final boolean blackhole;
		final List<State> next;

		Expansion(boolean blackhole, List<State> next) {
			this.blackhole = blackhole;
			this.next = next;
		}

		static Expansion empty() {
			return new Expansion(false, Collections.<State>emptyList());
		}

		static Expansion blackhole() {
			return new Expansion(true, Collections.<State>emptyList());
		}
	}
}
