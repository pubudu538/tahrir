package tahrir.io.net.broadcasts.containers;

import com.google.common.collect.Sets;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tahrir.io.net.broadcasts.broadcastMessages.BroadcastMessage;

import java.util.Comparator;
import java.util.PriorityQueue;
import java.util.Set;

public class BroadcastMessageOutbox {

    private static Logger logger = LoggerFactory.getLogger(BroadcastMessageOutbox.class);
	public final PriorityQueue<BroadcastMessage> outbox;
	private final Set<Integer> seen;

	public BroadcastMessageOutbox() {
		outbox = new PriorityQueue<BroadcastMessage>(100, new MicroblogPriorityComparator());
		seen = Sets.newLinkedHashSet();
	}

	public synchronized BroadcastMessage getMessageForBroadcast() {
		return outbox.poll();
	}

	public synchronized void changeBroadcastPriority(final BroadcastMessage mb, final int priority) {
		outbox.remove(mb);
		mb.priority = priority;
		outbox.add(mb);
	}

	public synchronized boolean isLikelyToContain(final int microblogHash) {
		return seen.contains(microblogHash);
	}

	public synchronized boolean contains(final BroadcastMessage mb) {
		return outbox.contains(mb);
	}

	public synchronized boolean insert(final BroadcastMessage mb) {
		final boolean inserted = false;

		seen.add(mb.hashCode());
		// this check probably isn't necessary but just to be sure...
		if (!outbox.contains(mb)) {
			// TODO: it doesn't check the size of the queue, it may get too big
			outbox.add(mb);
            logger.info("Added broadcast message to outbox.") ;
		}
		return inserted;
	}

	public synchronized boolean remove(final BroadcastMessage mb) {
		return outbox.remove(mb);
	}

	private class MicroblogPriorityComparator implements Comparator<BroadcastMessage> {
		@Override
		public int compare(final BroadcastMessage mb1, final BroadcastMessage mb2) {
			return Double.compare(mb1.priority, mb2.priority);
		}
	}
}