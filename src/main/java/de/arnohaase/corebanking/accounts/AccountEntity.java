package de.arnohaase.corebanking.accounts;

import akka.actor.ActorRef;
import akka.actor.PoisonPill;
import akka.actor.Props;
import akka.actor.ReceiveTimeout;
import akka.actor.Status.Failure;
import akka.cluster.sharding.ShardRegion;
import akka.pattern.PatternsCS;
import akka.persistence.AbstractPersistentActorWithAtLeastOnceDelivery;
import akka.persistence.AtLeastOnceDelivery;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import scala.concurrent.duration.FiniteDuration;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.concurrent.TimeUnit;


public class AccountEntity extends AbstractPersistentActorWithAtLeastOnceDelivery {
    private final Logger log = LoggerFactory.getLogger(getClass());

    public static Props props(ActorRef accounts, ActorRef transferWatchDog) {
        return Props.create(AccountEntity.class, () -> new AccountEntity(accounts, transferWatchDog));
    }

    private AccountEntity (ActorRef accounts, ActorRef transferWatchDog) {
        this.accounts = accounts;
        this.transferWatchDog = transferWatchDog;
        context().setReceiveTimeout(FiniteDuration.apply(10, TimeUnit.SECONDS));
    }

    @Override public String persistenceId () {
        return "account-" + self().path().name();
    }

    private final ActorRef accounts;
    private final ActorRef transferWatchDog;

    private boolean isCreated = false;
    private BigDecimal balance = BigDecimal.ZERO;

    private List<Object> journal = new ArrayList<>();


    @Override public Receive createReceive () {
        return receiveBuilder()
                .match(AccountMessages.CreateAccount.class, this::onCreate)
                .match(AccountMessages.Deposit.class, this::onDeposit)
                .match(AccountMessages.Withdraw.class, this::onWithdraw)
                .match(AccountMessages.Get.class, this::onGet)
                .match(AccountMessages.TransferCommand.class, this::onTransferCommand)
                .match(AccountMessages.ReceivedTransfer.class, this::onReceivedTransfer)
                .match(AccountMessages.TransferAckForSender.class, this::onTransferAckForSender)
                .match(AccountMessages.TransferPing.class, this::onPing)
                .match(AtLeastOnceDelivery.UnconfirmedWarning.class, msg -> log.error("this requires human attention"))
                .match(ReceiveTimeout.class, msg -> passivate())
                .build();
    }

    private void passivate() {
        context().parent().tell(new ShardRegion.Passivate (PoisonPill.getInstance()), self());
    }

    private void onPing(AccountMessages.TransferPing msg) {
        if (journal.stream().anyMatch(x ->
                (x instanceof AccountMessages.TransferAckForSender) &&
                ((AccountMessages.TransferAckForSender)x).transferId().equals(msg.transferId()))) {
            transferWatchDog.tell(ImmutableTransferPingCancellation.of(msg.transferId()), accounts);
        }
    }

    private void onTransferCommand(AccountMessages.TransferCommand msg) {
        if (msg.isWatched()) {
            persist(msg, evt -> {
                journal.add(msg);
                balance = balance.subtract(msg.amount());
                deliver(accounts.path(), deliveryId -> ImmutableReceivedTransfer.of(deliveryId, msg.transferId(), msg.amount(), msg.entityId(), Instant.now(), msg.targetAccount()));

                sender().tell(new AccountMessages.OK(), accounts);
            });
        }
        else {
            if (! isCreated)
                sender().tell(new Failure(new NoSuchElementException("account not created")), accounts);
            else if (msg.amount().signum() <= 0)
                sender().tell(new Failure(new IllegalArgumentException("amount must be positive")), accounts);
            else if (msg.amount().compareTo(balance) > 0)
                sender().tell(new Failure(new IllegalArgumentException("amount greater than balance")), accounts);
            else {
                final var f = PatternsCS.ask(transferWatchDog, msg, 5000);
                PatternsCS.pipe(f, context().dispatcher()).to(self(), sender());
            }
        }
    }

    private void onReceivedTransfer(AccountMessages.ReceivedTransfer msg) {
        if (! isCreated)
            accounts.tell(ImmutableTransferAckForSender.of(msg.deliveryId(), msg.transferId(), msg.amount(), false, Instant.now(), msg.sourceAccount()), accounts);
        else if(journal.stream().anyMatch(x -> (x instanceof AccountMessages.ReceivedTransfer) && ((AccountMessages.ReceivedTransfer)x).transferId().equals(msg.transferId())))
            accounts.tell(ImmutableTransferAckForSender.of(msg.deliveryId(), msg.transferId(), msg.amount(), true, Instant.now(), msg.sourceAccount()), accounts);
        else
            persist(msg, evt -> {
                journal.add(msg);
                balance = balance.add(msg.amount());
                accounts.tell(ImmutableTransferAckForSender.of(msg.deliveryId(), msg.transferId(), msg.amount(), true, Instant.now(), msg.sourceAccount()), accounts);
            });
    }
    private void onTransferAckForSender(AccountMessages.TransferAckForSender msg) {
        persist(msg, evt -> {
            journal.add(msg);
            if (! msg.accepted())
                balance = balance.add(msg.amount());
            transferWatchDog.tell(ImmutableTransferPingCancellation.of(msg.transferId()), accounts);
            confirmDelivery(msg.deliveryId());
        });
    }

    private void onCreate (Object msg) {
        if (isCreated)
            sender().tell(new Failure(new IllegalArgumentException("account already exists")), accounts);
        else
            persist(msg, evt -> {
                isCreated = true;
                sender().tell(msg, accounts);
            });
    }

    private void onDeposit (AccountMessages.Deposit msg) {
        if (! isCreated)
            sender().tell(new Failure(new NoSuchElementException("account not created")), accounts);
        else if (msg.amount().signum() <= 0)
            sender().tell(new Failure(new IllegalArgumentException("amount must be positive")), accounts);
        else
            persist(msg, evt -> {
                journal.add(evt);
                balance = balance.add(evt.amount());
                sender().tell(new AccountMessages.OK(), accounts);
            });
    }
    private void onWithdraw (AccountMessages.Withdraw msg) {
        if (! isCreated)
            sender().tell(new Failure(new NoSuchElementException("account not created")), accounts);
        else if (msg.amount().signum() <= 0)
            sender().tell(new Failure(new IllegalArgumentException("amount must be positive")), accounts);
        else if (msg.amount().compareTo(balance) > 0)
            sender().tell(new Failure(new IllegalArgumentException("amount greater than balance")), accounts);
        else
            persist(msg, evt -> {
                journal.add(evt);
                balance = balance.subtract(evt.amount());
                sender().tell(new AccountMessages.OK(), accounts);
            });
    }

    private void onGet (AccountMessages.Get msg) {
        if (! isCreated)
            sender().tell(new Failure(new NoSuchElementException("account not created")), accounts);
        else
            sender().tell(ImmutableGetResponse.of(balance, journal, msg.entityId()), accounts);
    }


    @Override public Receive createReceiveRecover () {
        return receiveBuilder()
            .match(AccountMessages.CreateAccount.class, x -> {
                isCreated=true;
            })
            .match(AccountMessages.Deposit.class, msg -> {
                journal.add(msg);
                balance = balance.add(msg.amount());
            })
            .match(AccountMessages.Withdraw.class, msg -> {
                journal.add(msg);
                balance = balance.subtract(msg.amount());
            })
            .match(AccountMessages.TransferCommand.class, msg -> {
                journal.add(msg);
                balance = balance.subtract(msg.amount());
                deliver(accounts.path(), deliveryId -> ImmutableReceivedTransfer.of(deliveryId, msg.transferId(), msg.amount(), msg.entityId(), Instant.now(), msg.targetAccount()));
            })
            .match(AccountMessages.ReceivedTransfer.class, msg -> {
                journal.add(msg);
                balance = balance.add(msg.amount());
            })
            .match(AccountMessages.TransferAckForSender.class, msg -> {
                journal.add(msg);
                if(! msg.accepted())
                    balance = balance.add(msg.amount());
                confirmDelivery(msg.deliveryId());
            })
            .build();
    }
}
