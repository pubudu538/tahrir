package tahrir.io.net.microblogging;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tahrir.TrNode;
import tahrir.io.net.PhysicalNetworkLocation;
import tahrir.io.net.TrSessionImpl;
import tahrir.io.net.TrSessionManager;
import tahrir.io.net.microblogging.microblogs.BroadcastMicroblog;

/**
 * A session for broadcasting a microblog to a node.
 * <p/>
 * The microblog will be broadcast if a node expresses interest based
 * on a probalistic condition otherwise the session will end.
 *
 * @author Kieran Donegan <kdonegan.92@gmail.com>
 */
public class MicroblogBroadcastSessionImpl extends TrSessionImpl implements MicroblogBroadcastSession {
	private static final Logger logger = LoggerFactory.getLogger(MicroblogBroadcastSessionImpl.class.getName());

	private BroadcastMicroblog microblogToBroadcast;

	private MicroblogBroadcastSession recipientSession;
	private MicroblogBroadcastSession senderSession;

	private boolean nextBroadcastStarted;

	public MicroblogBroadcastSessionImpl(final Integer sessionId, final TrNode node, final TrSessionManager sessionMgr) {
		super(sessionId, node, sessionMgr);
	}

	public void startSingleBroadcast(final BroadcastMicroblog mbToBroadcast, final PhysicalNetworkLocation recipientOfBroadcast) {
		nextBroadcastStarted = false;
		microblogToBroadcast = mbToBroadcast;
		recipientSession = remoteSession(MicroblogBroadcastSession.class, connection(recipientOfBroadcast));
		recipientSession.registerFailureListener(new OnFailureRun());
		recipientSession.areYouInterested(microblogToBroadcast.hashCode());
	}

	public void areYouInterested(final int mbHash) {
		senderSession = remoteSession(MicroblogBroadcastSession.class, connection(sender()));

		senderSession.interestIs(!node.mbClasses.mbsForBroadcast.isLikelyToContain(mbHash));
	}

	public void interestIs(final boolean interest) {
		if (interest) {
			recipientSession.sendMicroblog(microblogToBroadcast);
		} else {
			sessionFinished();
		}
	}

	public void sendMicroblog(final BroadcastMicroblog mb) {
		node.mbClasses.incomingMbHandler.handleInsertion(mb);
		// TODO: this is a workaround until we have a registerSuccessListener()
		senderSession.sessionFinished();
	}

	public void sessionFinished() {
		startBroadcastToNextPeer();
	}

	private synchronized void startBroadcastToNextPeer() {
		if (!nextBroadcastStarted) {
			nextBroadcastStarted = true;
			node.mbClasses.mbScheduler.startBroadcastToPeer();
		}
	}

	private class OnFailureRun implements Runnable {
		public void run() {
			startBroadcastToNextPeer();
		}
	}
}
