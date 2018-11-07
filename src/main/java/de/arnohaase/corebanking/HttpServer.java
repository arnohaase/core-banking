package de.arnohaase.corebanking;

import akka.actor.AbstractActor;
import akka.actor.ActorRef;
import akka.actor.Props;
import akka.http.javadsl.ConnectHttp;
import akka.http.javadsl.Http;
import akka.http.javadsl.marshallers.jackson.Jackson;
import akka.http.javadsl.model.StatusCodes;
import akka.http.javadsl.server.AllDirectives;
import akka.http.javadsl.server.ExceptionHandler;
import akka.http.javadsl.server.Route;
import akka.pattern.PatternsCS;
import akka.stream.ActorMaterializer;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.guava.GuavaModule;
import com.fasterxml.jackson.datatype.jdk8.Jdk8Module;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import de.arnohaase.corebanking.accounts.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.NoSuchElementException;
import java.util.UUID;

import static akka.http.javadsl.server.PathMatchers.*;


public class HttpServer extends AbstractActor {
    public static Props props(String host, int port, ActorRef accounts) {
        return Props.create(HttpServer.class, () -> new HttpServer(host, port, accounts));
    }

    private static final ObjectMapper om = new ObjectMapper();
    static {
        om.registerModule(new GuavaModule());
        om.registerModule(new Jdk8Module());
        om.registerModule(new JavaTimeModule());

        om.configure(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS, false);
    }

    public HttpServer (String host, int port, ActorRef accounts) {
        var http = Http.get(context().system());
        var mat = ActorMaterializer.create(context().system());

        var routeFlow = new Routes().createRoute(accounts).flow(context().system(), mat);
        http.bindAndHandle(routeFlow, ConnectHttp.toHost(host, port), mat);
    }


    @Override public Receive createReceive () {
        return AbstractActor.emptyBehavior();
    }

    private class Routes extends AllDirectives {
        private Route createRoute(ActorRef accounts) {
            return handleExceptions(
                ExceptionHandler.newBuilder()
                    .match(NoSuchElementException.class, exc -> complete(StatusCodes.NOT_FOUND))
                    .match(IllegalArgumentException.class, exc -> complete(StatusCodes.BAD_REQUEST))
                    .build(),
                () -> route (
                    pathPrefix("accounts", () -> route (
                        pathEnd(() -> post(() -> completeOKWithFuture(PatternsCS.ask(accounts, new AccountMessages.New(), 5000), Jackson.marshaller(om)))),
                        pathPrefix(uuidSegment(), accountId -> route (
                            pathEnd(() -> get(() -> completeOKWithFuture (PatternsCS.ask(accounts, ImmutableGet.of(accountId), 5000), Jackson.marshaller(om)))),
                            path(segment("deposits").slash(doubleSegment()), amount ->
                                post(() -> completeOKWithFuture(PatternsCS.ask(accounts, ImmutableDeposit.of(BigDecimal.valueOf(amount), Instant.now(), accountId), 5000), Jackson.marshaller(om)))
                            ),
                            path(segment("withdrawals").slash(doubleSegment()), amount ->
                                post(() -> completeOKWithFuture(PatternsCS.ask(accounts, ImmutableWithdraw.of(BigDecimal.valueOf(amount), Instant.now(), accountId), 5000), Jackson.marshaller(om)))
                            ),
                            path(segment("transfers").slash(uuidSegment()).slash(doubleSegment()), (targetAccount, amount) ->
                                post(() -> completeOKWithFuture(PatternsCS.ask(accounts, ImmutableTransferCommand.of(UUID.randomUUID(), false, BigDecimal.valueOf(amount), targetAccount, Instant.now(), accountId), 5000), Jackson.marshaller(om)))
                            )
                        ))
                    ))
                )
            );
        }
    }
}
