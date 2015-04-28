/*
 * Licensed to Elasticsearch under one or more contributor
 * license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright
 * ownership. Elasticsearch licenses this file to you under
 * the Apache License, Version 2.0 (the "License"); you may
 * not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.elasticsearch.transport.netty;

import io.netty.buffer.ByteBuf;
import io.netty.channel.*;
import org.apache.lucene.util.IOUtils;
import org.elasticsearch.Version;
import org.elasticsearch.common.component.Lifecycle;
import org.elasticsearch.common.compress.Compressor;
import org.elasticsearch.common.compress.CompressorFactory;
import org.elasticsearch.common.io.ThrowableObjectInputStream;
import org.elasticsearch.common.io.stream.StreamInput;
import org.elasticsearch.common.logging.ESLogger;
import org.elasticsearch.common.transport.InetSocketTransportAddress;
import org.elasticsearch.common.util.concurrent.AbstractRunnable;
import org.elasticsearch.threadpool.ThreadPool;
import org.elasticsearch.transport.*;
import org.elasticsearch.transport.support.TransportStatus;

import java.io.IOException;
import java.net.InetSocketAddress;

/**
 * A handler (must be the last one!) that does size based frame decoding and forwards the actual message
 * to the relevant action.
 */
public class MessageChannelHandler extends ChannelInboundHandlerAdapter {

    protected final ESLogger logger;
    protected final ThreadPool threadPool;
    protected final TransportServiceAdapter transportServiceAdapter;
    protected final NettyTransport transport;
    protected final String profileName;

    public MessageChannelHandler(NettyTransport transport, ESLogger logger, String profileName) {
        this.threadPool = transport.threadPool();
        this.transportServiceAdapter = transport.transportServiceAdapter();
        this.transport = transport;
        this.logger = logger;
        this.profileName = profileName;
    }

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
        if (!(msg instanceof ByteBuf)) {
            return;
        }
        ByteBuf buffer = (ByteBuf) msg;
        // spits a stack trace whenever close is called

        Transports.assertTransportThread();
        int size = buffer.readInt();
        transportServiceAdapter.received(size + 6);

        // we have additional bytes to read, outside of the header
        boolean hasMessageBytesToRead = (size - (NettyHeader.HEADER_SIZE - 6)) != 0;

        int markedReaderIndex = buffer.readerIndex();
        int expectedIndexReader = markedReaderIndex + size;

        // netty always copies a buffer, either in NioWorker in its read handler, where it copies to a fresh
        // buffer, or in the cumulation buffer, which is cleaned each time
        StreamInput streamIn = ByteBufStreamInputFactory.create(buffer, size);

        long requestId = buffer.readLong();
        byte status = buffer.readByte();
        Version version = Version.fromId(buffer.readInt());

        final StreamInput wrappedStream;
        if (TransportStatus.isCompress(status) && hasMessageBytesToRead && buffer.isReadable()) {
            Compressor compressor = CompressorFactory.compressor(buffer);
            if (compressor == null) {
                int maxToRead = Math.min(buffer.readableBytes(), 10);
                int offset = buffer.readerIndex();
                StringBuilder sb = new StringBuilder("stream marked as compressed, but no compressor found, first [").append(maxToRead).append("] content bytes out of [").append(buffer.readableBytes()).append("] readable bytes with message size [").append(size).append("] ").append("] are [");
                for (int i = 0; i < maxToRead; i++) {
                    sb.append(buffer.getByte(offset + i)).append(",");
                }
                sb.append("]");
                throw new IllegalStateException(sb.toString());
            }
            wrappedStream = compressor.streamInput(streamIn);
        } else {
            wrappedStream = streamIn;
        }
        wrappedStream.setVersion(version);

        String action = "";
        if (TransportStatus.isRequest(status)) {
            action = handleRequest(ctx.channel(), wrappedStream, requestId, version);
            if (buffer.readerIndex() != expectedIndexReader) {
                if (buffer.readerIndex() < expectedIndexReader) {
                    logger.warn("Message not fully read (request) for requestId [{}], action [{}], readerIndex [{}] vs expected [{}]; resetting",
                                requestId, action, buffer.readerIndex(), expectedIndexReader);
                } else {
                    logger.warn("Message read past expected size (request) for requestId=[{}], action [{}], readerIndex [{}] vs expected [{}]; resetting",
                                requestId, action, buffer.readerIndex(), expectedIndexReader);
                }
                buffer.readerIndex(expectedIndexReader);
            }
        } else {
            TransportResponseHandler handler = transportServiceAdapter.onResponseReceived(requestId);
            // ignore if its null, the adapter logs it
            if (handler != null) {
                if (TransportStatus.isError(status)) {
                    handlerResponseError(wrappedStream, handler);
                } else {
                    handleResponse(ctx.channel(), wrappedStream, handler);
                }
            } else {
                // if its null, skip those bytes
                buffer.readerIndex(markedReaderIndex + size);
            }
            if (buffer.readerIndex() != expectedIndexReader) {
                if (buffer.readerIndex() < expectedIndexReader) {
                    logger.warn("Message not fully read (response) for [{}] handler {}, error [{}], resetting", requestId, handler, TransportStatus.isError(status));
                } else {
                    logger.warn("Message read past expected size (response) for [{}] handler {}, error [{}], resetting", requestId, handler, TransportStatus.isError(status));
                }
                buffer.readerIndex(expectedIndexReader);
            }
        }
    }

    protected void handleResponse(Channel channel, StreamInput streamInput, final TransportResponseHandler handler) {
        final TransportResponse response = handler.newInstance();
        response.remoteAddress(new InetSocketTransportAddress((InetSocketAddress) channel.remoteAddress()));
        response.remoteAddress();
        try {
            response.readFrom(streamInput);
        } catch (Throwable e) {
            handleException(handler, new TransportSerializationException("Failed to deserialize response of type [" + response.getClass().getName() + "]", e), streamInput);
            return;
        }
        try {
            if (ThreadPool.Names.SAME.equals(handler.executor())) {
                //noinspection unchecked
                handler.handleResponse(response);
                streamInput.close();
                //logger.info("### handleResponse.sameThread ### closing, waiting for failure");
            } else {
                threadPool.executor(handler.executor()).execute(new ResponseHandler(handler, response, streamInput));
            }
        } catch (Throwable e) {
            handleException(handler, new ResponseHandlerFailureTransportException(e), streamInput);
        }
    }

    private void handlerResponseError(StreamInput buffer, final TransportResponseHandler handler) {
        Throwable error;
        try {
            ThrowableObjectInputStream ois = new ThrowableObjectInputStream(buffer, transport.settings().getClassLoader());
            error = (Throwable) ois.readObject();
        } catch (Throwable e) {
            error = new TransportSerializationException("Failed to deserialize exception response from stream", e);
        }
        handleException(handler, error, buffer);
    }

    private void handleException(final TransportResponseHandler handler, Throwable error, final StreamInput buffer) {
        if (!(error instanceof RemoteTransportException)) {
            error = new RemoteTransportException(error.getMessage(), error);
        }
        final RemoteTransportException rtx = (RemoteTransportException) error;
        if (ThreadPool.Names.SAME.equals(handler.executor())) {
            try {
                handler.handleException(rtx);
//                logger.info("### handleExceptionSameThread ### closing, waiting for failure");
                IOUtils.closeWhileHandlingException(buffer);
            } catch (Throwable e) {
                logger.error("failed to handle exception response [{}]", e, handler);
            }
        } else {
            threadPool.executor(handler.executor()).execute(new Runnable() {
                @Override
                public void run() {
                    try {
                        handler.handleException(rtx);
//                        logger.info("### handleExceptionSameThread ### closing, waiting for failure");
                        IOUtils.closeWhileHandlingException(buffer);
                    } catch (Throwable e) {
                        logger.error("failed to handle exception response [{}]", e, handler);
                    }
                }
            });
        }
    }

    protected String handleRequest(Channel channel, final StreamInput streamInput, long requestId, Version version) throws IOException {
        final String action = streamInput.readString();

        ChannelProgressivePromise progressPromise = channel.newProgressivePromise();
        progressPromise.addListener(new ChannelProgressiveFutureListener() {
            private volatile long writtenAmount;

            @Override
            public void operationComplete(ChannelProgressiveFuture future) throws Exception {
                if (future.isDone() && future.isSuccess()) {
                    transportServiceAdapter.sent(writtenAmount);
                }
            }

            @Override
            public void operationProgressed(ChannelProgressiveFuture future, long progress, long total) throws Exception {
                writtenAmount = progress;
            }

        });

        transportServiceAdapter.onRequestReceived(requestId, action);
        final NettyTransportChannel transportChannel = new NettyTransportChannel(transport, transportServiceAdapter, action, channel, requestId, version, profileName);
        try {
            final RequestHandlerRegistry reg = transportServiceAdapter.getRequestHandler(action);
            if (reg == null) {
                throw new ActionNotFoundTransportException(action);
            }
            final TransportRequest request = reg.newRequest();
            request.remoteAddress(new InetSocketTransportAddress((InetSocketAddress) channel.remoteAddress()));
            request.readFrom(streamInput);
            if (ThreadPool.Names.SAME.equals(reg.getExecutor())) {
                reg.getHandler().messageReceived(request, transportChannel);
                // TODO this blows up, because TransportShardReplicationOperationAction is going async, and we immediately close
                //streamInput.close();
            } else {
                threadPool.executor(reg.getExecutor()).execute(new RequestHandler(reg, request, transportChannel, action, streamInput));
            }
        } catch (Throwable e) {
            try {
                transportChannel.sendResponse(e);
            } catch (IOException e1) {
                logger.warn("Failed to send error message back to client for action [" + action + "]", e);
                logger.warn("Actual Exception", e1);
            }
        }
        return action;
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable e) throws Exception {
        transport.exceptionCaught(ctx, e);
    }

    class ResponseHandler implements Runnable {

        private final TransportResponseHandler handler;
        private final TransportResponse response;
        private StreamInput buffer;

        public ResponseHandler(TransportResponseHandler handler, TransportResponse response, StreamInput buffer) {
            this.handler = handler;
            this.response = response;
            this.buffer = buffer;
        }

        @SuppressWarnings({"unchecked"})
        @Override
        public void run() {
            try {
                handler.handleResponse(response);
                buffer.close();
                //logger.info("### ResponseHandler.run ### closing, waiting for failure");
            } catch (Throwable e) {
                handleException(handler, new ResponseHandlerFailureTransportException(e), buffer);
            }
        }
    }

    class RequestHandler extends AbstractRunnable {
        private final RequestHandlerRegistry reg;
        private final TransportRequest request;
        private final NettyTransportChannel transportChannel;
        private final String action;
        private final StreamInput streamInput;

        public RequestHandler(RequestHandlerRegistry reg, TransportRequest request, NettyTransportChannel transportChannel, String action, StreamInput streamInput) {
//        public RequestHandler(TransportRequestHandler handler, TransportRequest request, NettyTransportChannel transportChannel, String action) {
            this.reg = reg;
            this.request = request;
            this.transportChannel = transportChannel;
            this.action = action;
            this.streamInput = streamInput;
        }

        @SuppressWarnings({"unchecked"})
        @Override
        protected void doRun() throws Exception {
            reg.getHandler().messageReceived(request, transportChannel);
            streamInput.close();
        }

        @Override
        public boolean isForceExecution() {
            return reg.isForceExecution();
        }

        @Override
        public void onFailure(Throwable e) {
            if (transport.lifecycleState() == Lifecycle.State.STARTED) {
                // we can only send a response transport is started....
                try {
                    transportChannel.sendResponse(e);
                } catch (Throwable e1) {
                    logger.warn("Failed to send error message back to client for action [" + reg.getAction() + "]", e1);
                    logger.warn("Actual Exception", e);
                }
            }
        }
    }
}
