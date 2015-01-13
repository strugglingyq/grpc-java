/*
 * Copyright 2014, Google Inc. All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are
 * met:
 *
 *    * Redistributions of source code must retain the above copyright
 * notice, this list of conditions and the following disclaimer.
 *    * Redistributions in binary form must reproduce the above
 * copyright notice, this list of conditions and the following disclaimer
 * in the documentation and/or other materials provided with the
 * distribution.
 *
 *    * Neither the name of Google Inc. nor the names of its
 * contributors may be used to endorse or promote products derived from
 * this software without specific prior written permission.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS
 * "AS IS" AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT
 * LIMITED TO, THE IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR
 * A PARTICULAR PURPOSE ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT
 * OWNER OR CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL,
 * SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT
 * LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE,
 * DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY
 * THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
 * (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE
 * OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */

package com.google.net.stubby.transport;

import com.google.common.base.Preconditions;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.net.stubby.Metadata;
import com.google.net.stubby.Status;

import java.io.InputStream;
import java.nio.ByteBuffer;
import java.util.concurrent.Executor;
import java.util.logging.Level;
import java.util.logging.Logger;

import javax.annotation.Nullable;

/**
 * Abstract base class for {@link ServerStream} implementations.
 */
public abstract class AbstractServerStream<IdT> extends AbstractStream<IdT>
    implements ServerStream {
  private static final Logger log = Logger.getLogger(AbstractServerStream.class.getName());

  /** Whether listener.closed() has been called. */
  private boolean listenerClosed;
  private ServerStreamListener listener;

  private boolean headersSent = false;
  /**
   * Whether the stream was closed gracefully by the application (vs. a transport-level failure).
   */
  private boolean gracefulClose;
  /** Saved trailers from close() that need to be sent once the framer has sent all messages. */
  private Metadata.Trailers stashedTrailers;

  protected AbstractServerStream(IdT id, Executor deframerExecutor) {
    super(deframerExecutor);
    id(id);
  }

  public final void setListener(ServerStreamListener listener) {
    this.listener = Preconditions.checkNotNull(listener, "listener");
  }

  @Override
  protected ListenableFuture<Void> receiveMessage(InputStream is, int length) {
    inboundPhase(Phase.MESSAGE);
    return listener.messageRead(is, length);
  }

  @Override
  public void writeHeaders(Metadata.Headers headers) {
    Preconditions.checkNotNull(headers, "headers");
    outboundPhase(Phase.HEADERS);
    headersSent = true;
    internalSendHeaders(headers);
    outboundPhase(Phase.MESSAGE);
  }

  @Override
  public final void writeMessage(InputStream message, int length, @Nullable Runnable accepted) {
    if (!headersSent) {
      writeHeaders(new Metadata.Headers());
      headersSent = true;
    }
    super.writeMessage(message, length, accepted);
  }

  @Override
  public final void close(Status status, Metadata.Trailers trailers) {
    Preconditions.checkNotNull(status, "status");
    Preconditions.checkNotNull(trailers, "trailers");
    if (outboundPhase(Phase.STATUS) != Phase.STATUS) {
      gracefulClose = true;
      this.stashedTrailers = trailers;
      writeStatusToTrailers(status);
      closeFramer();
    }
  }

  private void writeStatusToTrailers(Status status) {
    stashedTrailers.removeAll(Status.CODE_KEY);
    stashedTrailers.removeAll(Status.MESSAGE_KEY);
    stashedTrailers.put(Status.CODE_KEY, status);
    if (status.getDescription() != null) {
      stashedTrailers.put(Status.MESSAGE_KEY, status.getDescription());
    }
  }

  /**
   * Called in the network thread to process the content of an inbound DATA frame from the client.
   *
   * @param frame the inbound HTTP/2 DATA frame. If this buffer is not used immediately, it must
   *              be retained.
   */
  public void inboundDataReceived(Buffer frame, boolean endOfStream) {
    if (inboundPhase() == Phase.STATUS) {
      frame.close();
      return;
    }
    // TODO(user): It sounds sub-optimal to deframe in the network thread. That means
    // decompression is serialized.
    deframe(frame, endOfStream);
  }

  @Override
  protected final void deframeFailed(Throwable cause) {
    log.log(Level.WARNING, "Exception processing message", cause);
    abortStream(Status.fromThrowable(cause), true);
  }

  @Override
  protected final void internalSendFrame(ByteBuffer frame, boolean endOfStream) {
    if (frame.hasRemaining()) {
      sendFrame(frame, false);
    }
    if (endOfStream) {
      sendTrailers(stashedTrailers, headersSent);
      headersSent = true;
      stashedTrailers = null;
    }
  }

  /**
   * Sends response headers to the remote end points.
   * @param headers to be sent to client.
   */
  protected abstract void internalSendHeaders(Metadata.Headers headers);

  /**
   * Sends an outbound frame to the remote end point.
   *
   * @param frame a buffer containing the chunk of data to be sent.
   * @param endOfStream if {@code true} indicates that no more data will be sent on the stream by
   *        this endpoint.
   */
  protected abstract void sendFrame(ByteBuffer frame, boolean endOfStream);

  /**
   * Sends trailers to the remote end point. This call implies end of stream.
   *
   * @param trailers metadata to be sent to end point
   * @param headersSent true if response headers have already been sent.
   */
  protected abstract void sendTrailers(Metadata.Trailers trailers, boolean headersSent);

  /**
   * The Stream is considered completely closed and there is no further opportunity for error. It
   * calls the listener's {@code closed()} if it was not already done by {@link #abortStream}. Note
   * that it is expected that either {@code closed()} or {@code abortStream()} was previously
   * called, since {@code closed()} is required for a normal stream closure and {@code
   * abortStream()} for abnormal.
   */
  public void complete() {
    listenerClosed = true;
    if (!gracefulClose) {
      listener.closed(Status.INTERNAL.withDescription("successful complete() without close()"));
      throw new IllegalStateException("successful complete() without close()");
    }
    listener.closed(Status.OK);
  }

  /**
   * Called when the remote end half-closes the stream.
   */
  @Override
  protected final void remoteEndClosed() {
    if (inboundPhase(Phase.STATUS) != Phase.STATUS) {
      listener.halfClosed();
    }
  }

  /**
   * Aborts the stream with an error status, cleans up resources and notifies the listener if
   * necessary.
   *
   * <p>Unlike {@link #close(Status, Metadata.Trailers)}, this method is only called from the
   * transport. The transport should use this method instead of {@code close(Status)} for internal
   * errors to prevent exposing unexpected states and exceptions to the application.
   *
   * @param status the error status. Must not be Status.OK.
   * @param notifyClient true if the stream is still writable and you want to notify the client
   *                     about stream closure and send the status
   */
  public final void abortStream(Status status, boolean notifyClient) {
    // TODO(user): Investigate whether we can remove the notification to the client
    // and rely on a transport layer stream reset instead.
    Preconditions.checkArgument(!status.isOk(), "status must not be OK");
    if (!listenerClosed) {
      listenerClosed = true;
      listener.closed(status);
    }
    if (notifyClient) {
      // TODO(user): Remove
      if (stashedTrailers == null) {
        stashedTrailers = new Metadata.Trailers();
      }
      writeStatusToTrailers(status);
      closeFramer();
    } else {
      dispose();
    }
  }

  @Override
  public boolean isClosed() {
    return super.isClosed() || listenerClosed;
  }
}