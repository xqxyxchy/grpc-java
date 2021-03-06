/*
 * Copyright 2017, gRPC Authors All rights reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.grpc.testing.integration;

import static junit.framework.TestCase.assertTrue;

import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.okhttp.OkHttpChannelBuilder;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.lang.reflect.Method;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import org.junit.After;
import org.junit.Before;
import org.junit.Ignore;
import org.junit.Test;
import org.junit.internal.AssumptionViolatedException;

/**
 * This servlet communicates with {@code grpc-test.sandbox.googleapis.com}, which is a server
 * managed by the gRPC team. For more information, see
 * <a href="https://github.com/grpc/grpc/blob/master/doc/interop-test-descriptions.md">
 *   Interoperability Test Case Descriptions</a>.
 */
@SuppressWarnings("serial")
public final class OkHttpClientInteropServlet extends HttpServlet {
  private static final String INTEROP_TEST_ADDRESS = "grpc-test.sandbox.googleapis.com:443";

  @Override
  public void doGet(HttpServletRequest req, HttpServletResponse resp) throws IOException {
    resp.setContentType("text/plain");
    PrintWriter writer = resp.getWriter();
    writer.println("Test invoked at: ");
    writer.println(new SimpleDateFormat("yyyy/MM/dd HH:mm:ss Z")
        .format(Calendar.getInstance().getTime()));

    // We can not use JUnit because it tries to spawn backgrounds threads.
    // GAE+JDK7 does not allow arbitrary background threads.
    // Let's use reflection to run test methods.
    Tester tester = new Tester();
    List<Method> befores = new ArrayList<>();
    List<Method> afters = new ArrayList<>();
    List<Method> testMethods = new ArrayList<>();
    int ignored = 0;
    for (Method method : tester.getClass().getMethods()) {
      if (method.getAnnotation(Test.class) != null) {
        if (method.getAnnotation(Ignore.class) != null) {
          ignored++;
        } else {
          testMethods.add(method);
        }
      } else if (method.getAnnotation(Before.class) != null) {
        befores.add(method);
      } else if (method.getAnnotation(After.class) != null) {
        afters.add(method);
      }
    }

    StringBuilder sb = new StringBuilder();
    int failures = 0;
    for (Method method : testMethods) {
      try {
        for (Method before : befores) {
          before.invoke(tester);
        }
        method.invoke(tester);
        for (Method after : afters) {
          after.invoke(tester);
        }
      } catch (Exception e) {
        // The default JUnit4 test runner skips tests with failed assumptions.
        // We will do the same here.
        boolean assumptionViolated = false;
        for (Throwable iter = e; iter != null; iter = iter.getCause()) {
          if (iter instanceof AssumptionViolatedException) {
            assumptionViolated = true;
            break;
          }
        }
        if (assumptionViolated) {
          continue;
        }

        sb.append("================\n");
        sb.append("Test method: ").append(method).append("\n");
        failures++;
        StringWriter stringWriter = new StringWriter();
        PrintWriter printWriter = new PrintWriter(stringWriter);
        e.printStackTrace(printWriter);
        sb.append(stringWriter);
      }
    }
    if (failures == 0) {
      resp.setStatus(200);
      writer.println(
          String.format(
              "PASS! Tests ran %d, tests ignored %d",
              testMethods.size(),
              ignored));
    } else {
      resp.setStatus(500);
      writer.println(
          String.format(
              "FAILED! Tests ran %d, tests failed %d, tests ignored %d",
              testMethods.size(),
              failures,
              ignored));
    }
    writer.println(sb);
  }

  public static final class Tester extends AbstractInteropTest {
    @Override
    protected ManagedChannel createChannel() {
      ManagedChannelBuilder<?> builder =
          ManagedChannelBuilder.forTarget(INTEROP_TEST_ADDRESS)
              .maxInboundMessageSize(AbstractInteropTest.MAX_MESSAGE_SIZE);
      assertTrue(builder instanceof OkHttpChannelBuilder);
      return builder.build();
    }

    @Override
    protected boolean metricsExpected() {
      // Server-side metrics won't be found because the server is running remotely.
      return false;
    }

    // grpc-test.sandbox.googleapis.com does not support these tests
    @Override
    public void customMetadata() { }

    @Override
    public void statusCodeAndMessage() { }

    @Override
    public void exchangeMetadataUnaryCall() { }

    @Override
    public void exchangeMetadataStreamingCall() { }
  }
}
