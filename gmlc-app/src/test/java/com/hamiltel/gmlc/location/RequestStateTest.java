package com.hamiltel.gmlc.location;

import org.junit.jupiter.api.Test;

import static com.hamiltel.gmlc.location.RequestState.ANSWERED;
import static com.hamiltel.gmlc.location.RequestState.AUTHORISED;
import static com.hamiltel.gmlc.location.RequestState.DELIVERED;
import static com.hamiltel.gmlc.location.RequestState.FAILED;
import static com.hamiltel.gmlc.location.RequestState.PRIVACY_CHECKED;
import static com.hamiltel.gmlc.location.RequestState.RECEIVED;
import static com.hamiltel.gmlc.location.RequestState.REQUESTED;
import static com.hamiltel.gmlc.location.RequestState.ROUTED;
import static com.hamiltel.gmlc.location.RequestState.TIMEOUT;
import static org.assertj.core.api.Assertions.assertThat;

class RequestStateTest {

    @Test
    void theHappyPathIsTheOnlyForwardEdgeFromEachState() {
        assertThat(RECEIVED.canMoveTo(AUTHORISED)).isTrue();
        assertThat(AUTHORISED.canMoveTo(PRIVACY_CHECKED)).isTrue();
        assertThat(PRIVACY_CHECKED.canMoveTo(ROUTED)).isTrue();
        assertThat(ROUTED.canMoveTo(REQUESTED)).isTrue();
        assertThat(REQUESTED.canMoveTo(ANSWERED)).isTrue();
        assertThat(ANSWERED.canMoveTo(DELIVERED)).isTrue();
    }

    @Test
    void aCellBasedAnswerMaySkipTheSignallingStep() {
        assertThat(ROUTED.canMoveTo(ANSWERED)).isTrue();
    }

    @Test
    void earlyStatesMayFailButCannotSkipToAnAnswer() {
        assertThat(RECEIVED.canMoveTo(FAILED)).isTrue();
        assertThat(AUTHORISED.canMoveTo(TIMEOUT)).isTrue();
        assertThat(PRIVACY_CHECKED.canMoveTo(FAILED)).isTrue();
        assertThat(RECEIVED.canMoveTo(ANSWERED)).isFalse();
        assertThat(AUTHORISED.canMoveTo(ANSWERED)).isFalse();
        assertThat(AUTHORISED.canMoveTo(ROUTED)).isFalse();
    }

    @Test
    void anAnsweredRequestCannotFailAfterwards() {
        assertThat(ANSWERED.canMoveTo(FAILED)).isFalse();
        assertThat(ANSWERED.canMoveTo(TIMEOUT)).isFalse();
        assertThat(REQUESTED.canMoveTo(DELIVERED)).isFalse();
    }

    @Test
    void terminalStatesCannotMove() {
        for (RequestState terminal : new RequestState[] {TIMEOUT, FAILED, DELIVERED}) {
            assertThat(terminal.isFinal()).isTrue();
            for (RequestState next : RequestState.values()) {
                assertThat(terminal.canMoveTo(next))
                        .as("%s -> %s", terminal, next)
                        .isFalse();
            }
        }
    }

    @Test
    void onlyTerminalStatesAreFinal() {
        assertThat(RECEIVED.isFinal()).isFalse();
        assertThat(ROUTED.isFinal()).isFalse();
        assertThat(REQUESTED.isFinal()).isFalse();
        assertThat(ANSWERED.isFinal()).isFalse();
    }
}
