/*
 * Copyright 2017-present Open Networking Foundation
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.atomix.protocols.raft.session.impl;

import java.net.ConnectException;
import java.nio.channels.ClosedChannelException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BiConsumer;
import java.util.function.Predicate;

import com.google.protobuf.Message;
import io.atomix.primitive.PrimitiveException;
import io.atomix.primitive.PrimitiveState;
import io.atomix.primitive.operation.PrimitiveOperation;
import io.atomix.protocols.raft.RaftException;
import io.atomix.protocols.raft.protocol.Command;
import io.atomix.protocols.raft.protocol.OperationRequest;
import io.atomix.protocols.raft.protocol.OperationResponse;
import io.atomix.protocols.raft.protocol.Query;
import io.atomix.protocols.raft.protocol.RaftError;
import io.atomix.protocols.raft.protocol.ResponseStatus;
import io.atomix.utils.concurrent.ThreadContext;

import static com.google.common.base.Preconditions.checkNotNull;

/**
 * Session operation submitter.
 */
final class RaftSessionInvoker {
  private static final int[] FIBONACCI = new int[]{1, 1, 2, 3, 5};
  private static final Predicate<Throwable> EXCEPTION_PREDICATE = e ->
      e instanceof ConnectException
          || e instanceof TimeoutException
          || e instanceof ClosedChannelException;
  private static final Predicate<Throwable> EXPIRED_PREDICATE = e ->
      e instanceof RaftException.UnknownClient
          || e instanceof RaftException.UnknownSession;
  private static final Predicate<Throwable> CLOSED_PREDICATE = e ->
      e instanceof RaftException.ClosedSession
          || e instanceof RaftException.UnknownService;

  private final RaftSessionConnection leaderConnection;
  private final RaftSessionConnection sessionConnection;
  private final RaftSessionState state;
  private final RaftSessionSequencer sequencer;
  private final RaftSessionManager manager;
  private final ThreadContext context;
  private final Map<Long, OperationAttempt> attempts = new LinkedHashMap<>();
  private final AtomicLong keepAliveIndex = new AtomicLong();

  RaftSessionInvoker(
      RaftSessionConnection leaderConnection,
      RaftSessionConnection sessionConnection,
      RaftSessionState state,
      RaftSessionSequencer sequencer,
      RaftSessionManager manager,
      ThreadContext context) {
    this.leaderConnection = checkNotNull(leaderConnection, "leaderConnection");
    this.sessionConnection = checkNotNull(sessionConnection, "sessionConnection");
    this.state = checkNotNull(state, "state");
    this.sequencer = checkNotNull(sequencer, "sequencer");
    this.manager = checkNotNull(manager, "manager");
    this.context = checkNotNull(context, "context cannot be null");
  }

  /**
   * Submits a operation to the cluster.
   *
   * @param operation The operation to submit.
   * @return A completable future to be completed once the command has been submitted.
   */
  public CompletableFuture<byte[]> invoke(PrimitiveOperation operation) {
    CompletableFuture<byte[]> future = new CompletableFuture<>();
    switch (operation.getId().getType()) {
      case COMMAND:
        context.execute(() -> invokeCommand(operation, future));
        break;
      case QUERY:
        context.execute(() -> invokeQuery(operation, future));
        break;
      default:
        throw new IllegalArgumentException("Unknown operation type " + operation.getId().getType());
    }
    return future;
  }

  /**
   * Submits a command to the cluster.
   */
  private void invokeCommand(PrimitiveOperation operation, CompletableFuture<byte[]> future) {
    OperationRequest request = OperationRequest.newBuilder()
        .setSessionId(state.getSessionId().id())
        .setSequenceNumber(state.nextCommandRequest())
        .setCommand(Command.newBuilder()
            .setName(operation.getId().getName())
            .setCommand(operation.getValue())
            .build())
        .build();
    invokeCommand(request, future);
  }

  /**
   * Submits a command request to the cluster.
   */
  private void invokeCommand(OperationRequest request, CompletableFuture<byte[]> future) {
    invoke(new CommandAttempt(sequencer.nextRequest(), request, future));
  }

  /**
   * Submits a query to the cluster.
   */
  private void invokeQuery(PrimitiveOperation operation, CompletableFuture<byte[]> future) {
    OperationRequest request = OperationRequest.newBuilder()
        .setSessionId(state.getSessionId().id())
        .setSequenceNumber(state.getCommandRequest())
        .setQuery(Query.newBuilder()
            .setName(operation.getId().getName())
            .setQuery(operation.getValue())
            .setIndex(Math.max(state.getResponseIndex(), state.getEventIndex()))
            .build())
        .build();
    invokeQuery(request, future);
  }

  /**
   * Submits a query request to the cluster.
   */
  private void invokeQuery(OperationRequest request, CompletableFuture<byte[]> future) {
    invoke(new QueryAttempt(sequencer.nextRequest(), request, future));
  }

  /**
   * Submits an operation attempt.
   *
   * @param attempt The attempt to submit.
   */
  private <T extends Message, U extends Message> void invoke(OperationAttempt attempt) {
    if (state.getState() == PrimitiveState.CLOSED) {
      attempt.fail(new PrimitiveException.ClosedSession("session closed"));
    } else {
      attempts.put(attempt.sequence, attempt);
      attempt.send();
      attempt.future.whenComplete((r, e) -> attempts.remove(attempt.sequence));
    }
  }

  /**
   * Resubmits commands starting after the given sequence number.
   * <p>
   * The sequence number from which to resend commands is the <em>request</em> sequence number, not the client-side
   * sequence number. We resend only commands since queries cannot be reliably resent without losing linearizable
   * semantics. Commands are resent by iterating through all pending operation attempts and retrying commands where the
   * sequence number is greater than the given {@code commandSequence} number and the attempt number is less than or
   * equal to the version.
   */
  private void resubmit(long commandSequence, OperationAttempt attempt) {
    // If the client's response sequence number is greater than the given command sequence number,
    // the cluster likely has a new leader, and we need to reset the sequencing in the leader by
    // sending a keep-alive request.
    // Ensure that the client doesn't resubmit many concurrent KeepAliveRequests by tracking the last
    // keep-alive response sequence number and only resubmitting if the sequence number has changed.
    long responseSequence = state.getCommandResponse();
    if (commandSequence < responseSequence && keepAliveIndex.get() != responseSequence) {
      keepAliveIndex.set(responseSequence);
      manager.resetIndexes(state.getSessionId()).whenCompleteAsync((result, error) -> {
        if (error == null) {
          resubmit(responseSequence, attempt);
        } else {
          keepAliveIndex.set(0);
          attempt.retry(Duration.ofSeconds(FIBONACCI[Math.min(attempt.attempt - 1, FIBONACCI.length - 1)]));
        }
      }, context);
    } else {
      for (Map.Entry<Long, OperationAttempt> entry : attempts.entrySet()) {
        OperationAttempt operation = entry.getValue();
        if (operation instanceof CommandAttempt
            && operation.request.getSequenceNumber() > commandSequence
            && operation.attempt <= attempt.attempt) {
          operation.retry();
        }
      }
    }
  }

  /**
   * Resubmits pending commands.
   */
  public void reset() {
    context.execute(() -> {
      for (OperationAttempt attempt : attempts.values()) {
        attempt.retry();
      }
    });
  }

  /**
   * Closes the submitter.
   *
   * @return A completable future to be completed with a list of pending operations.
   */
  public CompletableFuture<Void> close() {
    for (OperationAttempt attempt : new ArrayList<>(attempts.values())) {
      attempt.fail(new PrimitiveException.ClosedSession("session closed"));
    }
    attempts.clear();
    return CompletableFuture.completedFuture(null);
  }

  /**
   * Operation attempt.
   */
  private abstract class OperationAttempt implements BiConsumer<OperationResponse, Throwable> {
    protected final long sequence;
    protected final int attempt;
    protected final OperationRequest request;
    protected final CompletableFuture<byte[]> future;

    protected OperationAttempt(long sequence, int attempt, OperationRequest request, CompletableFuture<byte[]> future) {
      this.sequence = sequence;
      this.attempt = attempt;
      this.request = request;
      this.future = future;
    }

    /**
     * Sends the attempt.
     */
    protected abstract void send();

    /**
     * Returns the next instance of the attempt.
     *
     * @return The next instance of the attempt.
     */
    protected abstract OperationAttempt next();

    /**
     * Returns a new instance of the default exception for the operation.
     *
     * @return A default exception for the operation.
     */
    protected abstract Throwable defaultException();

    /**
     * Completes the operation successfully.
     *
     * @param response The operation response.
     */
    protected abstract void complete(OperationResponse response);

    /**
     * Completes the operation with an exception.
     *
     * @param error The completion exception.
     */
    protected void complete(Throwable error) {
      sequence(null, () -> future.completeExceptionally(error));
    }

    /**
     * Runs the given callback in proper sequence.
     *
     * @param response The operation response.
     * @param callback The callback to run in sequence.
     */
    protected final void sequence(OperationResponse response, Runnable callback) {
      sequencer.sequenceResponse(sequence, response, callback);
    }

    /**
     * Fails the attempt.
     */
    public void fail() {
      fail(defaultException());
    }

    /**
     * Fails the attempt with the given exception.
     *
     * @param t The exception with which to fail the attempt.
     */
    public void fail(Throwable t) {
      sequence(null, () -> {
        state.setCommandResponse(request.getSequenceNumber());
        future.completeExceptionally(t);
      });

      // If the session has been expired or closed, update the client's state.
      if (EXPIRED_PREDICATE.test(t)) {
        state.setState(PrimitiveState.EXPIRED);
      } else if (CLOSED_PREDICATE.test(t)) {
        state.setState(PrimitiveState.CLOSED);
      }
    }

    /**
     * Immediately retries the attempt.
     */
    public void retry() {
      context.execute(() -> invoke(next()));
    }

    /**
     * Retries the attempt after the given duration.
     *
     * @param after The duration after which to retry the attempt.
     */
    public void retry(Duration after) {
      context.schedule(after, () -> invoke(next()));
    }
  }

  /**
   * Command operation attempt.
   */
  private final class CommandAttempt extends OperationAttempt {
    CommandAttempt(long sequence, OperationRequest request, CompletableFuture<byte[]> future) {
      super(sequence, 1, request, future);
    }

    CommandAttempt(long sequence, int attempt, OperationRequest request, CompletableFuture<byte[]> future) {
      super(sequence, attempt, request, future);
    }

    @Override
    protected void send() {
      leaderConnection.command(request).whenComplete(this);
    }

    @Override
    protected OperationAttempt next() {
      return new CommandAttempt(sequence, this.attempt + 1, request, future);
    }

    @Override
    protected Throwable defaultException() {
      return new PrimitiveException.CommandFailure("failed to complete command");
    }

    @Override
    public void accept(OperationResponse response, Throwable error) {
      if (error == null) {
        if (response.getStatus() == ResponseStatus.OK) {
          complete(response);
        }
        // COMMAND_ERROR indicates that the command was received by the leader out of sequential order.
        // We need to resend commands starting at the provided lastSequence number.
        else if (response.getError() == RaftError.COMMAND_FAILURE) {
          resubmit(response.getLastSequenceNumber(), this);
        }
        // If a PROTOCOL_ERROR occurred, complete the request exceptionally with the error message.
        else if (response.getError() == RaftError.PROTOCOL_ERROR) {
          complete(new PrimitiveException.Unavailable(response.getMessage()));
        }
        // If an APPLICATION_ERROR occurred, complete the request with a ServiceException.
        else if (response.getError() == RaftError.APPLICATION_ERROR) {
          complete(new PrimitiveException.ServiceException(response.getMessage()));
        }
        // If the client is unknown by the cluster, close the session and complete the operation exceptionally.
        else if (response.getError() == RaftError.UNKNOWN_CLIENT
            || response.getError() == RaftError.UNKNOWN_SESSION) {
          complete(new PrimitiveException.UnknownSession(response.getMessage()));
          state.setState(PrimitiveState.EXPIRED);
        }
        // If the service is unknown by the cluster or the session was explicitly closed, set the session state to CLOSED.
        else if (response.getError() == RaftError.UNKNOWN_SERVICE
            || response.getError() == RaftError.CLOSED_SESSION) {
          complete(new PrimitiveException.UnknownService(response.getMessage()));
          state.setState(PrimitiveState.CLOSED);
        }
        // For all other errors, use fibonacci backoff to resubmit the command.
        else {
          retry(Duration.ofSeconds(FIBONACCI[Math.min(attempt - 1, FIBONACCI.length - 1)]));
        }
      } else if (EXCEPTION_PREDICATE.test(error) || (error instanceof CompletionException && EXCEPTION_PREDICATE.test(error.getCause()))) {
        if (error instanceof ConnectException || error.getCause() instanceof ConnectException) {
          leaderConnection.reset(null, leaderConnection.members());
        }
        retry(Duration.ofSeconds(FIBONACCI[Math.min(attempt - 1, FIBONACCI.length - 1)]));
      } else {
        fail(error);
      }
    }

    @Override
    @SuppressWarnings("unchecked")
    protected void complete(OperationResponse response) {
      sequence(response, () -> {
        state.setCommandResponse(request.getSequenceNumber());
        state.setResponseIndex(response.getIndex());
        future.complete(!response.getOutput().isEmpty() ? response.getOutput().toByteArray() : null);
      });
    }
  }

  /**
   * Query operation attempt.
   */
  private final class QueryAttempt extends OperationAttempt {
    QueryAttempt(long sequence, OperationRequest request, CompletableFuture<byte[]> future) {
      super(sequence, 1, request, future);
    }

    QueryAttempt(long sequence, int attempt, OperationRequest request, CompletableFuture<byte[]> future) {
      super(sequence, attempt, request, future);
    }

    @Override
    protected void send() {
      sessionConnection.query(request).whenComplete(this);
    }

    @Override
    protected OperationAttempt next() {
      return new QueryAttempt(sequence, this.attempt + 1, request, future);
    }

    @Override
    protected Throwable defaultException() {
      return new PrimitiveException.QueryFailure("failed to complete query");
    }

    @Override
    public void accept(OperationResponse response, Throwable error) {
      if (error == null) {
        if (response.getStatus() == ResponseStatus.OK) {
          complete(response);
        } else if (response.getError() == RaftError.UNKNOWN_CLIENT
            || response.getError() == RaftError.UNKNOWN_SESSION) {
          complete(new PrimitiveException.UnknownSession("Unknown session"));
          state.setState(PrimitiveState.EXPIRED);
        } else if (response.getError() == RaftError.UNKNOWN_SERVICE
            || response.getError() == RaftError.CLOSED_SESSION) {
          complete(new PrimitiveException.UnknownService("Unknown service"));
          state.setState(PrimitiveState.CLOSED);
        } else {
          complete(new PrimitiveException.QueryFailure("Query failed"));
        }
      } else {
        fail(error);
      }
    }

    @Override
    @SuppressWarnings("unchecked")
    protected void complete(OperationResponse response) {
      sequence(response, () -> {
        state.setResponseIndex(response.getIndex());
        future.complete(response.getOutput().toByteArray());
      });
    }
  }

}
