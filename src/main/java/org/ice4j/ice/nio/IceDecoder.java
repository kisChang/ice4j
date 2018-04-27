package org.ice4j.ice.nio;

import java.net.InetSocketAddress;
import java.net.SocketAddress;

import org.apache.mina.core.buffer.IoBuffer;
import org.apache.mina.core.session.IoSession;
import org.apache.mina.filter.codec.ProtocolDecoderAdapter;
import org.apache.mina.filter.codec.ProtocolDecoderOutput;
import org.ice4j.StunException;
import org.ice4j.StunMessageEvent;
import org.ice4j.Transport;
import org.ice4j.TransportAddress;
import org.ice4j.attribute.Attribute;
import org.ice4j.attribute.UsernameAttribute;
import org.ice4j.ice.nio.IceTransport.Ice;
import org.ice4j.message.Message;
import org.ice4j.socket.IceSocketWrapper;
import org.ice4j.stack.RawMessage;
import org.ice4j.stack.StunStack;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * This class handles the ice decoding.
 * 
 * @author Paul Gregoire
 */
public class IceDecoder extends ProtocolDecoderAdapter {

    private static final Logger logger = LoggerFactory.getLogger(IceDecoder.class);

    /**
     * Length of a DTLS record header.
     */
    private static final int DTLS_RECORD_HEADER_LENGTH = 13;

    @Override
    public void decode(IoSession session, IoBuffer in, ProtocolDecoderOutput out) throws Exception {
        //logger.trace("Decode start pos: {} session: {}", in.position(), session.getId());
        IoBuffer resultBuffer;
        IceSocketWrapper iceSocket = (IceSocketWrapper) session.getAttribute(Ice.CONNECTION);
        if (iceSocket != null) {
            //logger.trace("Decoding: {}", in);
            // SocketAddress from session are InetSocketAddress which fail cast to TransportAddress, so handle there here
            SocketAddress localAddr = session.getLocalAddress();
            if (localAddr instanceof InetSocketAddress) {
                InetSocketAddress inetAddr = (InetSocketAddress) localAddr;
                localAddr = new TransportAddress(inetAddr.getAddress(), inetAddr.getPort(), (session.getTransportMetadata().isConnectionless() ? Transport.UDP : Transport.TCP));
            }
            SocketAddress remoteAddr = session.getRemoteAddress();
            if (remoteAddr instanceof InetSocketAddress) {
                InetSocketAddress inetAddr = (InetSocketAddress) remoteAddr;
                remoteAddr = new TransportAddress(inetAddr.getAddress(), inetAddr.getPort(), (session.getTransportMetadata().isConnectionless() ? Transport.UDP : Transport.TCP));
            }
            // there is incoming data from the socket, decode it
            // get the incoming bytes
            byte[] buf = new byte[in.remaining()];
            // get the bytes into our buffer
            in.get(buf);
            // STUN messages are at least 20 bytes and DTLS are 13+
            if (buf.length > DTLS_RECORD_HEADER_LENGTH) {
                // if its a stun message, process it
                if (isStun(buf)) {
                    if (logger.isTraceEnabled()) {
                        logger.trace("Dispatching a STUN message");
                    }
                    StunStack stunStack = (StunStack) session.getAttribute(Ice.STUN_STACK);
                    try {
                        // create a message
                        RawMessage message = RawMessage.build(buf, remoteAddr, localAddr);
                        Message stunMessage = Message.decode(message.getBytes(), 0, message.getMessageLength());
                        StunMessageEvent stunMessageEvent = new StunMessageEvent(stunStack, message, stunMessage);
                        stunStack.handleMessageEvent(stunMessageEvent);
                    } catch (StunException ex) {
                        logger.warn("Failed to decode a stun message!", ex);
                    }
                } else if (isDtls(buf)) {
                    logger.trace("Byte buffer length: {} {}", buf.length, in);
                    int offset = 0;
                    do {
                        if (logger.isTraceEnabled()) {
                            short contentType = (short) (buf[offset] & 0xff);
                            if (contentType == DtlsContentType.handshake) {
                                short messageType = (short) (buf[offset + 11] & 0xff);
                                logger.trace("DTLS handshake message type: {}", getMessageType(messageType));
                            }
                        }
                        // get the length of the dtls record
                        int dtlsRecordLength = (buf[offset + 11] & 0xff) << 8 | (buf[offset + 12] & 0xff);
                        byte[] record = new byte[dtlsRecordLength + DTLS_RECORD_HEADER_LENGTH];
                        System.arraycopy(buf, offset, record, 0, record.length);
                        if (logger.isTraceEnabled()) {
                            String dtlsVersion = getDtlsVersion(buf, 0, buf.length);
                            logger.trace("Queuing DTLS {} length: {} message: {}", dtlsVersion, dtlsRecordLength, StunStack.toHexString(record));
                        }
                        // create a message
                        RawMessage message = RawMessage.build(record, remoteAddr, localAddr);
                        iceSocket.getRawMessageQueue().offer(message);
                        // increment the offset
                        offset += record.length;
                        logger.trace("Offset: {}", offset);
                    } while (offset < (buf.length - DTLS_RECORD_HEADER_LENGTH));
                } else {
                    // this should catch anything else not readily identified as stun or dtls
                    iceSocket.getRawMessageQueue().offer(RawMessage.build(buf, remoteAddr, localAddr));
                }
            } else {
                // there was not enough data in the buffer to parse - this should never happen
                logger.warn("Not enough data in the buffer to parse: {}", in);
            }
        } else {
            // no connection, pass through
            logger.warn("No ice socket in session");
            resultBuffer = IoBuffer.wrap(in.array(), 0, in.limit());
            in.position(in.limit());
            out.write(resultBuffer);
        }
    }

    /*
     * TCP has a 2b prefix containing its size Receives an RFC4571-formatted frame from channel into p, and sets p's port and address to the remote port and address of this Socket.
     * public void receive(DatagramPacket p) throws IOException { SocketChannel socketChannel = (SocketChannel) channel; while (frameLengthByteBuffer.hasRemaining()) { int read =
     * socketChannel.read(frameLengthByteBuffer); if (read == -1) { throw new SocketException("Failed to receive data from socket."); } } frameLengthByteBuffer.flip(); int b0 =
     * frameLengthByteBuffer.get(); int b1 = frameLengthByteBuffer.get(); int frameLength = ((b0 & 0xFF) << 8) | (b1 & 0xFF); frameLengthByteBuffer.flip(); byte[] data =
     * p.getData(); if (data == null || data.length < frameLength) { data = new byte[frameLength]; } ByteBuffer byteBuffer = ByteBuffer.wrap(data, 0, frameLength); while
     * (byteBuffer.hasRemaining()) { int read = socketChannel.read(byteBuffer); if (read == -1) { throw new SocketException("Failed to receive data from socket."); } }
     * p.setAddress(socketChannel.socket().getInetAddress()); p.setData(data, 0, frameLength); p.setPort(socketChannel.socket().getPort()); }
     */

    /**
     * Determines whether data in a byte array represents a STUN message.
     *
     * @param buf the bytes to check
     * @return true if the bytes look like STUN, otherwise false
     */
    public boolean isStun(byte[] buf) {
        // If this is a STUN packet
        boolean isStunPacket = false;
        // All STUN messages MUST start with a 20-byte header followed by zero or more Attributes
        if (buf.length >= 20) {
            // If the MAGIC COOKIE is present this is a STUN packet (RFC5389 compliant).
            if (buf[4] == Message.MAGIC_COOKIE[0] && buf[5] == Message.MAGIC_COOKIE[1] && buf[6] == Message.MAGIC_COOKIE[2] && buf[7] == Message.MAGIC_COOKIE[3]) {
                isStunPacket = true;
            } else {
                // Else, this packet may be a STUN packet (RFC3489 compliant). To determine this, we must continue the checks.
                // The most significant 2 bits of every STUN message MUST be zeroes.  This can be used to differentiate STUN packets from
                // other protocols when STUN is multiplexed with other protocols on the same port.
                byte b0 = buf[0];
                boolean areFirstTwoBitsValid = ((b0 & 0xC0) == 0);
                // Checks if the length of the data correspond to the length field of the STUN header. The message length field of the
                // STUN header does not include the 20-byte of the STUN header.
                int total_header_length = ((((int) buf[2]) & 0xff) << 8) + (((int) buf[3]) & 0xff) + 20;
                boolean isHeaderLengthValid = (buf.length == total_header_length);
                isStunPacket = areFirstTwoBitsValid && isHeaderLengthValid;
            }
        }
        if (isStunPacket) {
            byte b0 = buf[0];
            byte b1 = buf[1];
            // we only accept the method Binding and the reserved methods 0x000 and 0x002/SharedSecret
            int method = (b0 & 0xFE) | (b1 & 0xEF);
            switch (method) {
                case Message.STUN_METHOD_BINDING:
                case Message.STUN_REQUEST:
                case Message.SHARED_SECRET_REQUEST:
                    return true;
            }
        }
        return false;
    }

    /**
     * Determines whether data in a byte array represents a DTLS message.
     *
     * @param buf the bytes to check
     * @return true if the bytes look like DTLS, otherwise false
     */
    public boolean isDtls(byte[] buf) {
        if (buf.length > 0) {
            int fb = buf[0] & 0xff;
            return 19 < fb && fb < 64;
        }
        return false;
    }

    /**
     * Returns the DTLS version as a string or null if parsing fails.
     * 
     * @param buf the bytes to probe
     * @param offset data start position
     * @param length data length
     * @return DTLS version or null
     */
    public static String getDtlsVersion(byte[] buf, int offset, int length) {
        String version = null;
        // DTLS record header length is 13b
        if (length >= DTLS_RECORD_HEADER_LENGTH) {
            short type = (short) (buf[offset] & 0xff);
            switch (type) {
                case DtlsContentType.alert:
                case DtlsContentType.application_data:
                case DtlsContentType.change_cipher_spec:
                case DtlsContentType.handshake:
                    int major = buf[offset + 1] & 0xff;
                    int minor = buf[offset + 2] & 0xff;
                    //logger.trace("Version: {}.{}", major, minor);
                    // DTLS v1.0
                    if (major == 254 && minor == 255) {
                        version = "1.0";
                    }
                    // DTLS v1.2
                    if (version == null && major == 254 && minor == 253) {
                        version = "1.2";
                    }
                    break;
                default:
                    logger.trace("Unhandled content type: {}", type);
                    break;
            }
        }
        //logger.debug("DtlsRecord: {} length: {}", version, length);
        return version;
    }

    /**
     * Tries to parse the bytes in buf at offset off (and length len) as a STUN Binding Request message. If successful,
     * looks for a USERNAME attribute and returns the local username fragment part (see RFC5245 Section 7.1.2.3).
     * In case of any failure returns null.
     *
     * @param buf the bytes.
     * @param off the offset.
     * @param len the length.
     * @return the local ufrag from the USERNAME attribute of the STUN message contained in buf, or null.
     */
    public static String getUfrag(byte[] buf, int off, int len) {
        // RFC5389, Section 6: All STUN messages MUST start with a 20-byte header followed by zero or more Attributes.
        if (buf == null || buf.length < off + len || len < 20) {
            return null;
        }
        // RFC5389, Section 6: The magic cookie field MUST contain the fixed value 0x2112A442 in network byte order.
        if (((buf[off + 4] & 0xFF) == 0x21 && (buf[off + 5] & 0xFF) == 0x12 && (buf[off + 6] & 0xFF) == 0xA4 && (buf[off + 7] & 0xFF) == 0x42)) {
            try {
                Message stunMessage = Message.decode(buf, off, len);
                if (stunMessage.getMessageType() == Message.BINDING_REQUEST) {
                    UsernameAttribute usernameAttribute = (UsernameAttribute) stunMessage.getAttribute(Attribute.Type.USERNAME);
                    if (logger.isTraceEnabled()) {
                        logger.trace("UsernameAttribute: {}", usernameAttribute);
                    }
                    if (usernameAttribute != null) {
                        String usernameString = new String(usernameAttribute.getUsername());
                        return usernameString.split(":")[0];
                    }
                }
            } catch (Exception e) {
                // Catch everything. We are going to log, and then drop the packet anyway.
                if (logger.isDebugEnabled()) {
                    logger.warn("Failed to extract local ufrag", e);
                }
            }
        } else {
            if (logger.isDebugEnabled()) {
                logger.debug("Not a STUN packet, magic cookie not found.");
            }
        }
        return null;
    }

    String getMessageType(short msg_type) {
        switch (msg_type) {
            case HandshakeType.hello_request: // 0;
                return "Hello request";
            case HandshakeType.client_hello: // 1;
                return "Client hello";
            case HandshakeType.server_hello: // 2;
                return "Server hello";
            case HandshakeType.hello_verify_request: // 3;
                return "Hello verify request";
            case HandshakeType.session_ticket: // 4;
                return "Session ticket";
            case HandshakeType.certificate: // 11;
                return "Certificate";
            case HandshakeType.server_key_exchange: // 12;
                return "Server key exchange";
            case HandshakeType.certificate_request: // 13;
                return "Certificate request";
            case HandshakeType.server_hello_done: // 14;
                return "Server hello done";
            case HandshakeType.certificate_verify: // 15;
                return "Certificate verify";
            case HandshakeType.client_key_exchange: // 16;
                return "Client key exchange";
            case HandshakeType.finished: // 20;
                return "Finished";
            case HandshakeType.certificate_url: // 21;
                return "Certificate url";
            case HandshakeType.certificate_status: // 22;
                return "Certificate status";
            case HandshakeType.supplemental_data: // 23;
                return "Supplemental data";
        }
        return null;
    }

    /**
     * RFC 2246 6.2.1
     */
    class DtlsContentType {

        public static final short change_cipher_spec = 20; // 14

        public static final short alert = 21; // 15

        public static final short handshake = 22; // 16

        public static final short application_data = 23; // 17

        public static final short heartbeat = 24; // 18
    }

    class HandshakeType {
        public static final short hello_request = 0; // 0;

        public static final short client_hello = 1; // 1;

        public static final short server_hello = 2; // 2;

        public static final short hello_verify_request = 3; // 3;

        public static final short session_ticket = 4; // 4;

        public static final short certificate = 17; // 11;

        public static final short server_key_exchange = 18; // 12;

        public static final short certificate_request = 19; // 13;

        public static final short server_hello_done = 20; // 14;

        public static final short certificate_verify = 21; // 15;

        public static final short client_key_exchange = 22; // 16;

        public static final short finished = 32; // 20;

        public static final short certificate_url = 33; // 21;

        public static final short certificate_status = 34; // 22;

        public static final short supplemental_data = 35; // 23;
    }
}
