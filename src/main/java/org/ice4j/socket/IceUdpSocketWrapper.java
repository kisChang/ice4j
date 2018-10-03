/* See LICENSE.md for license information */
package org.ice4j.socket;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.InetAddress;
import java.net.SocketAddress;
import java.nio.channels.ClosedChannelException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import org.apache.mina.core.buffer.IoBuffer;
import org.apache.mina.core.future.WriteFuture;
import org.apache.mina.core.session.IoSession;
import org.apache.mina.transport.socket.nio.NioDatagramAcceptor;
import org.ice4j.TransportAddress;
import org.ice4j.ice.nio.IceDecoder;
import org.ice4j.ice.nio.IceUdpTransport;
import org.ice4j.stack.RawMessage;

/**
 * UDP implementation of the IceSocketWrapper.
 *
 * @author Paul Gregoire
 */
public class IceUdpSocketWrapper extends IceSocketWrapper {

    /**
     * Utilized during connect on a new send to prevent reentrant connect attempts
     */
    private AtomicBoolean connecting = new AtomicBoolean(false);

    /**
     * Constructor.
     */
    public IceUdpSocketWrapper() {
    }

    /**
     * Constructor.
     *
     * @param address TransportAddress
     * @throws IOException 
     */
    public IceUdpSocketWrapper(TransportAddress address) throws IOException {
        transportAddress = address;
    }

    /** {@inheritDoc} */
    @SuppressWarnings("static-access")
    @Override
    public void newSession(SocketAddress destAddress) {
        logger.debug("newSession: {}", destAddress);
        // look for an existing acceptor
        IceUdpTransport transport = IceUdpTransport.getInstance(getId());
        NioDatagramAcceptor acceptor = (NioDatagramAcceptor) transport.getAcceptor();
        if (acceptor != null) {
            try {
                // if the ports not bound, bind it
                logger.info("Transport is bound: {}", transport.isBound(transportAddress.getPort()));
                if (!transport.isBound(transportAddress.getPort())) {
                    transport.addBinding(transportAddress);
                }
                // check for session
                IoSession sess = getSession();
                if (sess == null) {
                    // attempt to create a server session, if it fails the local address isn't bound
                    setSession(acceptor.newSession(destAddress, transportAddress));
                    // count down since we have a session
                    connectLatch.countDown();
                } else {
                    logger.info("Session {} already connected", sess.getId());
                }
            } catch (Exception e) {
                logger.warn("Exception creating new session using acceptor for {}", transportAddress, e);
            }
        } else {
            logger.debug("No existing UDP acceptor available");
        }
    }

    /** {@inheritDoc} */
    @Override
    public void send(IoBuffer buf, SocketAddress destAddress) throws IOException {
        if (isClosed()) {
            logger.debug("Connection is closed");
            throw new ClosedChannelException();
        } else {
            if (logger.isTraceEnabled()) {
                logger.trace("send: {} to: {}", buf, destAddress);
            }
            // write future for ensuring write/send
            WriteFuture writeFuture = null;
            try {
                // if we're not relaying, proceed with normal flow
                if (relayedCandidateConnection == null || IceDecoder.isTurnMethod(buf.array())) {
                    IoSession sess = getSession();
                    if (sess != null) {
                        // ensure that the destination matches the session remote
                        if (destAddress.equals(sess.getRemoteAddress())) {
                            writeFuture = sess.write(buf, destAddress);
                            writeFuture.addListener(writeListener);
                        } else {
                            // look thru stale sessions for a match
                            staleSessions.forEach(stale -> {
                                if (destAddress.equals(stale.getRemoteAddress())) {
                                    if (logger.isTraceEnabled()) {
                                        logger.trace("Sending to stale session: {}", destAddress);
                                    }
                                    stale.write(buf, destAddress);
                                    return;
                                }
                            });
                        }
                    } else {
                        logger.debug("No session, attempting connect from: {} to: {}", transportAddress, destAddress);
                        // if we've not attempted to connect yet
                        if (connecting.compareAndSet(false, true)) {
                            // if we're not bound, attempt to create a client session
                            newSession(destAddress);
                            logger.debug("New session request completed");
                        }
                        // wait up-to x milliseconds for a connection to be established
                        if (connectLatch.await(3000, TimeUnit.MILLISECONDS)) {
                            // attempt to get a newly added session from connect process
                            sess = getSession();
                            if (sess != null) {
                                writeFuture = sess.write(buf, destAddress);
                                writeFuture.addListener(writeListener);
                            } else {
                                logger.warn("Send failed due to null session");
                            }
                        } else {
                            logger.warn("Send failed due to connection timeout");
                        }
                    }
                } else {
                    if (logger.isTraceEnabled()) {
                        logger.trace("Relayed send: {} to: {}", buf, destAddress);
                    }
                    relayedCandidateConnection.send(buf, destAddress);
                }
            } catch (Throwable t) {
                logger.warn("Exception attempting to send", t);
            } finally {
                if (writeFuture != null) {
                    writeFuture.removeListener(writeListener);
                }
            }
        }
    }

    /** {@inheritDoc} */
    @Override
    public void send(DatagramPacket p) throws IOException {
        send(IoBuffer.wrap(p.getData(), p.getOffset(), p.getLength()), p.getSocketAddress());
    }

    /** {@inheritDoc} */
    @Override
    public void receive(DatagramPacket p) throws IOException {
        RawMessage message = rawMessageQueue.poll();
        if (message != null) {
            p.setData(message.getBytes(), 0, message.getMessageLength());
            p.setSocketAddress(message.getRemoteAddress());
        }
    }

    /** {@inheritDoc} */
    @Override
    public RawMessage read() {
        return rawMessageQueue.poll();
    }

    /** {@inheritDoc} */
    @Override
    public InetAddress getLocalAddress() {
        return transportAddress.getAddress();
    }

    /** {@inheritDoc} */
    @Override
    public int getLocalPort() {
        return transportAddress.getPort();
    }

    @Override
    public String toString() {
        return "IceUdpSocketWrapper [transportAddress=" + transportAddress + ", session=" + getSession() + "]";
    }

}
