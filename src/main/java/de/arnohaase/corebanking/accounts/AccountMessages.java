package de.arnohaase.corebanking.accounts;

import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import org.immutables.value.Value;

import java.io.Serializable;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;


public class AccountMessages {
    public static class New {
    }
    public static class OK {
        public Instant getTimestamp() {
            return Instant.now();
        }
    }

    public interface WithEntityId extends Serializable {
        @Value.Parameter UUID entityId();
    }

    @JsonSerialize
    @Value.Immutable
    public interface CreateAccount extends WithEntityId {
    }

    @JsonSerialize
    @Value.Immutable
    public interface Deposit extends WithEntityId {
        default String getKind() { return "deposit"; }
        @Value.Parameter BigDecimal amount();
        @Value.Parameter Instant timestamp();
    }

    @JsonSerialize
    @Value.Immutable
    public interface Withdraw extends WithEntityId {
        default String getKind() { return "withdrawal"; }
        @Value.Parameter BigDecimal amount();
        @Value.Parameter Instant timestamp();
    }

    @JsonSerialize
    @Value.Immutable
    public interface Get extends WithEntityId {
    }

    @JsonSerialize
    @Value.Immutable
    public interface GetResponse extends WithEntityId {
        @Value.Parameter BigDecimal balance();
        @Value.Parameter List<Object> journal();
    }

    @JsonSerialize
    @Value.Immutable
    public interface TransferCommand extends WithEntityId {
        default String getKind() { return "transfer"; }
        @Value.Parameter UUID transferId();
        @Value.Parameter boolean isWatched();
        @Value.Parameter BigDecimal amount();
        @Value.Parameter UUID targetAccount();
        @Value.Parameter Instant timestamp();
    }
    @JsonSerialize
    @Value.Immutable
    public interface ReceivedTransfer extends WithEntityId {
        default String getKind() { return "received-transfer"; }
        @Value.Parameter long deliveryId();
        @Value.Parameter UUID transferId();
        @Value.Parameter BigDecimal amount();
        @Value.Parameter UUID sourceAccount();
        @Value.Parameter Instant timestamp();
    }
    @JsonSerialize
    @Value.Immutable
    public interface TransferAckForSender extends WithEntityId {
        default String getKind() { return "transfer-ack"; }
        @Value.Parameter long deliveryId();
        @Value.Parameter UUID transferId();
        @Value.Parameter BigDecimal amount();
        @Value.Parameter boolean accepted();
        @Value.Parameter Instant timestamp();
    }

    @JsonSerialize
    @Value.Immutable
    public interface TransferPing extends WithEntityId {
        @Value.Parameter UUID transferId();
    }
    @JsonSerialize
    @Value.Immutable
    public interface TransferPingCancellation extends Serializable {
        @Value.Parameter UUID transferId();
    }
}
