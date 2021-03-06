/*
 * Copyright 2016-2018 The OpenZipkin Authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License
 * is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express
 * or implied. See the License for the specific language governing permissions and limitations under
 * the License.
 */
package zipkin2.propagation.stackdriver;

import brave.propagation.Propagation;
import brave.propagation.TraceContext;
import brave.propagation.TraceContextOrSamplingFlags;
import java.util.logging.Level;
import java.util.logging.Logger;

import static brave.internal.HexCodec.lenientLowerHexToUnsignedLong;

public final class XCloudTraceContextExtractor<C, K> implements TraceContext.Extractor<C> {

  private static final Logger LOG = Logger.getLogger(XCloudTraceContextExtractor.class.getName());

  private final StackdriverTracePropagation<K> propagation;
  private final Propagation.Getter<C, K> getter;

  public XCloudTraceContextExtractor(
      StackdriverTracePropagation<K> propagation, Propagation.Getter<C, K> getter) {
    this.propagation = propagation;
    this.getter = getter;
  }

  /**
   * Creates a tracing context if the extracted string follows the "x-cloud-trace-context:
   * TRACE_ID" or "x-cloud-trace-context: TRACE_ID/SPAN_ID" format; or the
   * "x-cloud-trace-context: TRACE_ID/SPAN_ID;0=TRACE_TRUE" format and {@code TRACE_TRUE}'s value is
   * {@code 1}.
   */
  @Override
  public TraceContextOrSamplingFlags extract(C carrier) {
    if (carrier == null) throw new NullPointerException("carrier == null");

    TraceContextOrSamplingFlags result = TraceContextOrSamplingFlags.EMPTY;

    String xCloudTraceContext = getter.get(carrier, propagation.getTraceIdKey());

    if (xCloudTraceContext != null) {
      String[] tokens = xCloudTraceContext.split("/");

      // Try to parse the trace IDs into the context
      TraceContext.Builder context = TraceContext.newBuilder();
      long[] traceId = convertHexTraceIdToLong(tokens[0]);
      // traceId is null if invalid
      if (traceId != null) {
        boolean traceTrue = true;

        String spanId = "1";
        // A span ID exists. A TRACE_TRUE flag also possibly exists.
        if (tokens.length >= 2) {
          int semicolonPos = tokens[1].indexOf(";");
          spanId = semicolonPos == -1 ? tokens[1] : tokens[1].substring(0, semicolonPos);
          traceTrue = semicolonPos == -1
                  || tokens[1].length() == semicolonPos + 4
                  && tokens[1].charAt(semicolonPos + 3) == '1';
        }

        if (traceTrue) {
          result = TraceContextOrSamplingFlags.create(
                  context.traceIdHigh(traceId[0])
                          .traceId(traceId[1])
                          .spanId(parseUnsignedLong(spanId))
                          .build());
        }
      }
    }

    return result;
  }

  private long[] convertHexTraceIdToLong(String hexTraceId) {
    long[] result = new long[2];
    int length = hexTraceId.length();

    if (length != 32) return null;

    // left-most characters, if any, are the high bits
    int traceIdIndex = Math.max(0, length - 16);

    result[0] = lenientLowerHexToUnsignedLong(hexTraceId, 0, traceIdIndex);
    if (result[0] == 0) {
      if (LOG.isLoggable(Level.FINE)) {
        LOG.fine(hexTraceId + " is not a lower hex string.");
      }
      return null;
    }

    // right-most up to 16 characters are the low bits
    result[1] = lenientLowerHexToUnsignedLong(hexTraceId, traceIdIndex, length);
    if (result[1] == 0) {
      if (LOG.isLoggable(Level.FINE)) {
        LOG.fine(hexTraceId + " is not a lower hex string.");
      }
      return null;
    }
    return result;
  }

  /** Strictly parses unsigned numbers without a java 8 dependency. */
  static long parseUnsignedLong(String input) throws NumberFormatException {
    if (input == null) throw new NumberFormatException("input == null");
    int len = input.length();
    if (len == 0) throw new NumberFormatException("empty input");
    if (len > 20) throw new NumberFormatException("too long for uint64: " + input);

    // Bear in mind the following:
    // * maximum int64  is  9223372036854775807. Note it is 19 characters
    // * maximum uint64 is 18446744073709551615. Note it is 20 characters

    // It is safe to use defaults to parse <= 18 characters.
    if (len <= 18) return Long.parseLong(input);

    // we now know it is 19 or 20 characters: safely parse the left 18 characters
    long left = Long.parseLong(input.substring(0, 18));

    int digit19 = digitAt(input, 18);
    int rightDigits = 20 - len;
    if (rightDigits == 1) {
      return left * 10 + digit19; // even 19 9's fit safely in a uint64
    }

    int digit20 = digitAt(input, 19);
    int right = digit19 * 10 + digit20;
    // we can run into trouble if the 18 character prefix is greater than the prefix of the
    // maximum uint64, or the remaining two digits will make the number overflow
    // Reminder, largest uint64 is 18446744073709551615
    if (left > 184467440737095516L || (left == 184467440737095516L && right > 15)) {
      throw new NumberFormatException("out of range for uint64: " + input);
    }
    return left * 100 + right; // we are safe!
  }

  private static int digitAt(String input, int position) {
    if (input.length() <= position) throw new NumberFormatException("position out of bounds");

    switch (input.charAt(position)) {
      case '0' : return 0;
      case '1' : return 1;
      case '2' : return 2;
      case '3' : return 3;
      case '4' : return 4;
      case '5' : return 5;
      case '6' : return 6;
      case '7' : return 7;
      case '8' : return 8;
      case '9' : return 9;
      default: throw new NumberFormatException("char at position " + position + "("
              + input.charAt(position) + ") isn't a number");
    }
  }
}
