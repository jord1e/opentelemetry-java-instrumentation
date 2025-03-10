/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package io.opentelemetry.instrumentation.api.server;

import io.opentelemetry.api.trace.Span;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.ContextKey;
import io.opentelemetry.instrumentation.api.instrumenter.ContextCustomizer;
import io.opentelemetry.semconv.trace.attributes.SemanticAttributes;
import javax.annotation.Nullable;

// TODO: move to ...instrumenter.http and rename to HttpRouteHolder (?)
/** Helper container for tracking whether instrumentation should update server span name or not. */
public final class ServerSpanNaming {

  private static final ContextKey<ServerSpanNaming> CONTEXT_KEY =
      ContextKey.named("opentelemetry-http-server-route-key");

  public static <REQUEST> ContextCustomizer<REQUEST> get() {
    return (context, request, startAttributes) -> {
      if (context.get(CONTEXT_KEY) != null) {
        return context;
      }
      return context.with(CONTEXT_KEY, new ServerSpanNaming(Source.CONTAINER));
    };
  }

  private volatile Source updatedBySource;
  @Nullable private volatile String route;

  private ServerSpanNaming(Source initialSource) {
    this.updatedBySource = initialSource;
  }

  /**
   * If there is a server span in the context, and the context has been customized with a {@code
   * ServerSpanName}, then this method will update the server span name using the provided {@link
   * ServerSpanNameSupplier} if and only if the last {@link Source} to update the span name using
   * this method has strictly lower priority than the provided {@link Source}, and the value
   * returned from the {@link ServerSpanNameSupplier} is non-null.
   *
   * <p>If there is a server span in the context, and the context has NOT been customized with a
   * {@code ServerSpanName}, then this method will update the server span name using the provided
   * {@link ServerSpanNameSupplier} if the value returned from it is non-null.
   */
  public static <T> void updateServerSpanName(
      Context context, Source source, ServerSpanNameSupplier<T> serverSpanName, T arg1) {
    updateServerSpanName(context, source, OneArgAdapter.getInstance(), arg1, serverSpanName);
  }

  /**
   * If there is a server span in the context, and the context has been customized with a {@code
   * ServerSpanName}, then this method will update the server span name using the provided {@link
   * ServerSpanNameTwoArgSupplier} if and only if the last {@link Source} to update the span name
   * using this method has strictly lower priority than the provided {@link Source}, and the value
   * returned from the {@link ServerSpanNameTwoArgSupplier} is non-null.
   *
   * <p>If there is a server span in the context, and the context has NOT been customized with a
   * {@code ServerSpanName}, then this method will update the server span name using the provided
   * {@link ServerSpanNameTwoArgSupplier} if the value returned from it is non-null.
   */
  public static <T, U> void updateServerSpanName(
      Context context,
      Source source,
      ServerSpanNameTwoArgSupplier<T, U> serverSpanName,
      T arg1,
      U arg2) {
    Span serverSpan = ServerSpan.fromContextOrNull(context);
    // checking isRecording() is a helpful optimization for more expensive suppliers
    // (e.g. Spring MVC instrumentation's HandlerAdapterInstrumentation)
    if (serverSpan == null || !serverSpan.isRecording()) {
      return;
    }
    ServerSpanNaming serverSpanNaming = context.get(CONTEXT_KEY);
    if (serverSpanNaming == null) {
      String name = serverSpanName.get(context, arg1, arg2);
      if (name != null && !name.isEmpty()) {
        updateSpanData(serverSpan, name);
      }
      return;
    }
    // special case for servlet filters, even when we have a name from previous filter see whether
    // the new name is better and if so use it instead
    boolean onlyIfBetterName =
        !source.useFirst && source.order == serverSpanNaming.updatedBySource.order;
    if (source.order > serverSpanNaming.updatedBySource.order || onlyIfBetterName) {
      String name = serverSpanName.get(context, arg1, arg2);
      if (name != null
          && !name.isEmpty()
          && (!onlyIfBetterName || serverSpanNaming.isBetterName(name))) {
        updateSpanData(serverSpan, name);
        serverSpanNaming.updatedBySource = source;
        serverSpanNaming.route = name;
      }
    }
  }

  // TODO: instead of calling setAttribute() consider storing the route in context end retrieving it
  // in the AttributesExtractor
  private static void updateSpanData(Span serverSpan, String route) {
    serverSpan.updateName(route);
    serverSpan.setAttribute(SemanticAttributes.HTTP_ROUTE, route);
  }

  // This is used when setting name from a servlet filter to pick the most descriptive (longest)
  // route.
  private boolean isBetterName(String name) {
    String route = this.route;
    int routeLength = route == null ? 0 : route.length();
    return name.length() > routeLength;
  }

  // TODO: use that in HttpServerMetrics
  @Nullable
  public static String getRoute(Context context) {
    ServerSpanNaming serverSpanNaming = context.get(CONTEXT_KEY);
    return serverSpanNaming == null ? null : serverSpanNaming.route;
  }

  public enum Source {
    CONTAINER(1),
    // for servlet filters we try to find the best name which isn't necessarily from the first
    // filter that is called
    FILTER(2, /* useFirst= */ false),
    SERVLET(3),
    CONTROLLER(4),
    // Some frameworks, e.g. JaxRS, allow for nested controller/paths and we want to select the
    // longest one
    NESTED_CONTROLLER(5, false);

    private final int order;
    private final boolean useFirst;

    Source(int order) {
      this(order, /* useFirst= */ true);
    }

    Source(int order, boolean useFirst) {
      this.order = order;
      this.useFirst = useFirst;
    }
  }

  private static class OneArgAdapter<T>
      implements ServerSpanNameTwoArgSupplier<T, ServerSpanNameSupplier<T>> {

    private static final OneArgAdapter<Object> INSTANCE = new OneArgAdapter<>();

    @SuppressWarnings("unchecked")
    static <T> OneArgAdapter<T> getInstance() {
      return (OneArgAdapter<T>) INSTANCE;
    }

    @Override
    @Nullable
    public String get(Context context, T arg, ServerSpanNameSupplier<T> serverSpanNameSupplier) {
      return serverSpanNameSupplier.get(context, arg);
    }
  }
}
