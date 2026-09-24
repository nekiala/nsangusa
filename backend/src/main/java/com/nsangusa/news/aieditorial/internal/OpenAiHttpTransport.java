package com.nsangusa.news.aieditorial.internal;

import java.io.ByteArrayOutputStream;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.springframework.web.client.ResourceAccessException;

final class OpenAiHttpTransport {
  private OpenAiHttpTransport() {}

  static HttpResponse<byte[]> exchange(
      HttpClient client, HttpRequest request, int maxBytes, Duration timeout) {
    var future = client.sendAsync(request, ignored -> new BoundedBody(maxBytes));
    try {
      return future.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
    } catch (InterruptedException exception) {
      future.cancel(true);
      Thread.currentThread().interrupt();
      throw new AiProviderException("interrupted");
    } catch (TimeoutException exception) {
      future.cancel(true);
      throw new ResourceAccessException("AI provider response deadline exceeded");
    } catch (ExecutionException exception) {
      if (exception.getCause() instanceof AiProviderException rejected) {
        throw rejected;
      }
      throw new ResourceAccessException("AI provider transport failed");
    }
  }

  private static final class BoundedBody implements HttpResponse.BodySubscriber<byte[]> {
    private final int limit;
    private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    private final CompletableFuture<byte[]> result = new CompletableFuture<>();
    private Flow.Subscription subscription;

    BoundedBody(int limit) {
      this.limit = limit;
    }

    @Override
    public CompletionStage<byte[]> getBody() {
      return result;
    }

    @Override
    public void onSubscribe(Flow.Subscription subscription) {
      this.subscription = subscription;
      subscription.request(1);
    }

    @Override
    public void onNext(List<ByteBuffer> buffers) {
      for (ByteBuffer buffer : buffers) {
        if (buffer.remaining() > limit - bytes.size()) {
          subscription.cancel();
          result.completeExceptionally(new AiProviderException("response_too_large"));
          return;
        }
        byte[] chunk = new byte[buffer.remaining()];
        buffer.get(chunk);
        bytes.writeBytes(chunk);
      }
      subscription.request(1);
    }

    @Override
    public void onError(Throwable error) {
      result.completeExceptionally(error);
    }

    @Override
    public void onComplete() {
      result.complete(bytes.toByteArray());
    }
  }
}
