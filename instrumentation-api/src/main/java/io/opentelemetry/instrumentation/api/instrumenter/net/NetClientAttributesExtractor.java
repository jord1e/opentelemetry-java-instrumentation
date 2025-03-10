/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package io.opentelemetry.instrumentation.api.instrumenter.net;

import io.opentelemetry.api.common.AttributesBuilder;
import io.opentelemetry.instrumentation.api.instrumenter.AttributesExtractor;
import io.opentelemetry.semconv.trace.attributes.SemanticAttributes;
import javax.annotation.Nullable;

/**
 * Extractor of <a
 * href="https://github.com/open-telemetry/opentelemetry-specification/blob/main/specification/trace/semantic_conventions/span-general.md#general-network-connection-attributes">Network
 * attributes</a>. It is common to have access to {@link java.net.InetSocketAddress}, in which case
 * it is more convenient to use {@link InetSocketAddressNetClientAttributesExtractor}.
 */
public abstract class NetClientAttributesExtractor<REQUEST, RESPONSE>
    implements AttributesExtractor<REQUEST, RESPONSE> {

  @Override
  public final void onStart(AttributesBuilder attributes, REQUEST request) {}

  @Override
  public final void onEnd(
      AttributesBuilder attributes,
      REQUEST request,
      @Nullable RESPONSE response,
      @Nullable Throwable error) {

    set(attributes, SemanticAttributes.NET_TRANSPORT, transport(request, response));

    String peerIp = peerIp(request, response);
    String peerName = peerName(request, response);

    if (peerName != null && !peerName.equals(peerIp)) {
      set(attributes, SemanticAttributes.NET_PEER_NAME, peerName);
    }
    set(attributes, SemanticAttributes.NET_PEER_IP, peerIp);

    Integer peerPort = peerPort(request, response);
    if (peerPort != null && peerPort > 0) {
      set(attributes, SemanticAttributes.NET_PEER_PORT, (long) peerPort);
    }
  }

  @Nullable
  public abstract String transport(REQUEST request, @Nullable RESPONSE response);

  @Nullable
  public abstract String peerName(REQUEST request, @Nullable RESPONSE response);

  @Nullable
  public abstract Integer peerPort(REQUEST request, @Nullable RESPONSE response);

  @Nullable
  public abstract String peerIp(REQUEST request, @Nullable RESPONSE response);
}
