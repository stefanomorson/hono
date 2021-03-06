/*******************************************************************************
 * Copyright (c) 2016, 2018 Contributors to the Eclipse Foundation
 *
 * See the NOTICE file(s) distributed with this work for additional
 * information regarding copyright ownership.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 2.0 which is available at
 * http://www.eclipse.org/legal/epl-2.0
 *
 * SPDX-License-Identifier: EPL-2.0
 *******************************************************************************/

package org.eclipse.hono.client.impl;

import static org.hamcrest.CoreMatchers.is;
import static org.junit.Assert.assertThat;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

import java.net.HttpURLConnection;
import java.util.function.Predicate;
import java.util.function.Supplier;

import org.apache.qpid.proton.amqp.transport.AmqpError;
import org.apache.qpid.proton.amqp.transport.ErrorCondition;
import org.apache.qpid.proton.engine.Record;
import org.apache.qpid.proton.engine.impl.RecordImpl;
import org.eclipse.hono.client.ClientErrorException;
import org.eclipse.hono.client.ServerErrorException;
import org.eclipse.hono.client.ServiceInvocationException;
import org.eclipse.hono.config.ClientConfigProperties;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.Timeout;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;

import io.vertx.core.Context;
import io.vertx.core.Future;
import io.vertx.core.Handler;
import io.vertx.core.Vertx;
import io.vertx.ext.unit.TestContext;
import io.vertx.ext.unit.junit.VertxUnitRunner;
import io.vertx.proton.ProtonConnection;
import io.vertx.proton.ProtonQoS;
import io.vertx.proton.ProtonReceiver;
import io.vertx.proton.ProtonSender;


/**
 * Tests verifying behavior of {@link AbstractHonoClient}.
 *
 */
@RunWith(VertxUnitRunner.class)
public class AbstractHonoClientTest {

    /**
     * Time out each test after five seconds.
     */
    @Rule
    public final Timeout timeout = Timeout.seconds(5);

    private Vertx vertx;
    private Context context;
    private ClientConfigProperties props;

    /**
     * Sets up the fixture.
     */
    @Before
    public void setUp() {
        vertx = mock(Vertx.class);
        props = new ClientConfigProperties();
        context = HonoClientUnitTestHelper.mockContext(vertx);
    }

    /**
     * Verifies that the attempt to create a sender fails with a
     * {@code ServiceInvocationException} if the remote peer refuses
     * to open the link with an error condition.
     * 
     * @param ctx The vert.x test context.
     */
    @Test
    public void testCreateSenderFailsForErrorCondition(final TestContext ctx) {

        testCreateSenderFails(() -> (ErrorCondition) new ErrorCondition(AmqpError.RESOURCE_LIMIT_EXCEEDED, "unauthorized"), cause -> {
            return cause instanceof ServiceInvocationException;
        });
    }

    /**
     * Verifies that the attempt to create a sender fails with a
     * {@code ClientErrorException} if the remote peer refuses
     * to open the link without an error condition.
     * 
     * @param ctx The vert.x test context.
     */
    @Test
    public void testCreateSenderFailsWithoutErrorCondition(final TestContext ctx) {

        testCreateSenderFails(() -> (ErrorCondition) null, cause -> {
            return cause instanceof ClientErrorException &&
                ((ClientErrorException) cause).getErrorCode() == HttpURLConnection.HTTP_NOT_FOUND;
        });
    }

    @SuppressWarnings({ "unchecked", "rawtypes" })
    private void testCreateSenderFails(final Supplier<ErrorCondition> errorSupplier, final Predicate<Throwable> failureAssertion) {

        final Record attachments = new RecordImpl();
        final ProtonSender sender = mock(ProtonSender.class);
        when(sender.getRemoteCondition()).thenReturn(errorSupplier.get());
        when(sender.attachments()).thenReturn(attachments);
        final ProtonConnection con = mock(ProtonConnection.class);
        when(con.createSender(anyString())).thenReturn(sender);

        final Future<ProtonSender> result = AbstractHonoClient.createSender(context, props, con,
                "target", ProtonQoS.AT_LEAST_ONCE, null);

        verify(vertx).setTimer(eq(props.getLinkEstablishmentTimeout()), any(Handler.class));
        final ArgumentCaptor<Handler> openHandler = ArgumentCaptor.forClass(Handler.class);
        verify(sender).openHandler(openHandler.capture());
        openHandler.getValue().handle(Future.failedFuture(new IllegalStateException()));
        assertTrue(result.failed());
        assertTrue(failureAssertion.test(result.cause()));
    }

    /**
     * Verifies that the attempt to create a sender fails with a
     * {@code ServerErrorException} if the remote peer doesn't
     * send its attach frame in time.
     * 
     * @param ctx The vert.x test context.
     */
    @SuppressWarnings("unchecked")
    @Test
    public void testCreateSenderFailsOnTimeout(final TestContext ctx) {

        final Record attachments = new RecordImpl();
        final ProtonSender sender = mock(ProtonSender.class);
        when(sender.attachments()).thenReturn(attachments);
        final ProtonConnection con = mock(ProtonConnection.class);
        when(con.createSender(anyString())).thenReturn(sender);
        // run any timer immediately
        doAnswer(invocation -> {
            final Handler<Void> handler = invocation.getArgument(1);
            handler.handle(null);
            return 1L;
        }).when(vertx).setTimer(anyLong(), any(Handler.class));

        final Future<ProtonSender> result = AbstractHonoClient.createSender(context, props, con,
                "target", ProtonQoS.AT_LEAST_ONCE, null);
        assertTrue(result.failed());
        assertThat(((ServerErrorException) result.cause()).getErrorCode(), is(HttpURLConnection.HTTP_UNAVAILABLE));
        verify(sender).open();
        verify(sender).close();
        verify(sender).free();
    }

    /**
     * Verifies that the attempt to create a receiver fails with a
     * {@code ServiceInvocationException} if the remote peer refuses
     * to open the link with an error condition.
     * 
     * @param ctx The vert.x test context.
     */
    @Test
    public void testCreateReceiverFailsForErrorCondition(final TestContext ctx) {

        testCreateReceiverFails(() -> new ErrorCondition(AmqpError.RESOURCE_LIMIT_EXCEEDED, "unauthorized"), cause -> {
            return cause instanceof ServiceInvocationException;
        });
    }

    /**
     * Verifies that the attempt to create a receiver fails with a
     * {@code ClientErrorException} if the remote peer refuses
     * to open the link without an error condition.
     * 
     * @param ctx The vert.x test context.
     */
    @Test
    public void testCreateReceiverFailsWithoutErrorCondition(final TestContext ctx) {

        testCreateReceiverFails(() -> (ErrorCondition) null, cause -> {
            return cause instanceof ClientErrorException &&
                    ((ClientErrorException) cause).getErrorCode() == HttpURLConnection.HTTP_NOT_FOUND;
        });
    }

    @SuppressWarnings({ "unchecked", "rawtypes" })
    private void testCreateReceiverFails(final Supplier<ErrorCondition> errorSupplier, final Predicate<Throwable> failureAssertion) {

        final Record attachments = new RecordImpl();
        final ProtonReceiver receiver = mock(ProtonReceiver.class);
        when(receiver.getRemoteCondition()).thenReturn(errorSupplier.get());
        when(receiver.attachments()).thenReturn(attachments);
        final ProtonConnection con = mock(ProtonConnection.class);
        when(con.createReceiver(anyString())).thenReturn(receiver);

        final Future<ProtonReceiver> result = AbstractHonoClient.createReceiver(context, props, con,
                "source", ProtonQoS.AT_LEAST_ONCE, (delivery, msg) -> {}, null);

        verify(vertx).setTimer(eq(props.getLinkEstablishmentTimeout()), any(Handler.class));
        final ArgumentCaptor<Handler> openHandler = ArgumentCaptor.forClass(Handler.class);
        verify(receiver).openHandler(openHandler.capture());
        openHandler.getValue().handle(Future.failedFuture(new IllegalStateException()));
        assertTrue(result.failed());
        assertTrue(failureAssertion.test(result.cause()));
    }

    /**
     * Verifies that the attempt to create a receiver fails with a
     * {@code ServerErrorException} if the remote peer doesn't
     * send its attach frame in time.
     * 
     * @param ctx The vert.x test context.
     */
    @SuppressWarnings("unchecked")
    @Test
    public void testCreateReceiverFailsOnTimeout(final TestContext ctx) {

        final Record attachments = new RecordImpl();
        final ProtonReceiver receiver = mock(ProtonReceiver.class);
        when(receiver.attachments()).thenReturn(attachments);
        final ProtonConnection con = mock(ProtonConnection.class);
        when(con.createReceiver(anyString())).thenReturn(receiver);
        // run any timer immediately
        doAnswer(invocation -> {
            final Handler<Void> handler = invocation.getArgument(1);
            handler.handle(null);
            return 1L;
        }).when(vertx).setTimer(anyLong(), any(Handler.class));

        final Future<ProtonReceiver> result = AbstractHonoClient.createReceiver(context, props, con,
                "source", ProtonQoS.AT_LEAST_ONCE, (delivery, msg) -> {}, null);
        assertTrue(result.failed());
        assertThat(((ServerErrorException) result.cause()).getErrorCode(), is(HttpURLConnection.HTTP_UNAVAILABLE));
        verify(receiver).open();
        verify(receiver).close();
        verify(receiver).free();
    }

}
