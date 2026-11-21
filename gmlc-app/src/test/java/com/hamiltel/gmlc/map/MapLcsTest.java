package com.hamiltel.gmlc.map;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

class MapLcsTest {

    /** A Ugandan E.164 number: 256 77 1234567. */
    private static final String MSISDN = "256771234567";
    /** The same number in TBCD, prefixed with the international address octet. */
    private static final byte[] MSISDN_ADDRESS = bytes(
            0x91, 0x52, 0x76, 0x17, 0x32, 0x54, 0x76);

    @Test
    void aRoutingEnquiryIsAnMlcNumberThenTheTargetSubscriber() {
        byte[] argument = MapLcs.routingInfoForLcs(MSISDN, null, MSISDN);

        assertThat(argument).isEqualTo(Ber.sequence(
                bytes(0x80, 0x07, 0x91, 0x52, 0x76, 0x17, 0x32, 0x54, 0x76),
                bytes(0xa1, 0x09, 0x81, 0x07, 0x91, 0x52, 0x76, 0x17, 0x32, 0x54, 0x76)));
    }

    @Test
    void aRoutingAnswerNamesTheServingNodeAndTheMmeWhenTheNetworkIsEps() {
        byte[] mscNumber = MapLcs.address("256772000001");
        byte[] argument = Ber.sequence(
                Ber.tlv(Ber.contextConstructed(0),
                        Ber.tlv(Ber.context(1), MSISDN_ADDRESS)),
                Ber.tlv(Ber.contextConstructed(1), Ber.concat(
                        Ber.octets(mscNumber),
                        Ber.nullValue(Ber.context(2)),
                        Ber.tlv(Ber.context(6),
                                "mme1.epc.mnc001.mcc001.3gppnetwork.org"
                                        .getBytes(StandardCharsets.US_ASCII)))));

        MapLcs.RoutingInfo info = MapLcs.parseRoutingInfoForLcs(argument);

        assertThat(info.imsi()).isNull();
        assertThat(info.msisdn()).isEqualTo(MSISDN);
        assertThat(info.networkNode()).isEqualTo("256772000001");
        assertThat(info.gprsNode()).isTrue();
        assertThat(info.mmeName()).isEqualTo("mme1.epc.mnc001.mcc001.3gppnetwork.org");
        assertThat(info.sgsnNamePresent()).isFalse();
    }

    @Test
    void anImsiTargetInTheRoutingAnswerDecodesToo() {
        byte[] argument = Ber.sequence(
                Ber.tlv(Ber.contextConstructed(0),
                        Ber.tlv(Ber.context(0), MapLcs.imsiBytes("001010123456789"))),
                Ber.tlv(Ber.contextConstructed(1),
                        Ber.octets(MapLcs.address("256772000001"))));

        MapLcs.RoutingInfo info = MapLcs.parseRoutingInfoForLcs(argument);

        assertThat(info.imsi()).isEqualTo("001010123456789");
        assertThat(info.msisdn()).isNull();
        assertThat(info.gprsNode()).isFalse();
    }

    @Test
    void aProvideLocationRequestCarriesCurrentLocationTheClientAndTheAccuracy() {
        byte[] argument = MapLcs.provideSubscriberLocation(MSISDN, "001010123456789", null,
                3000, "gmlc");

        assertThat(argument).isEqualTo(Ber.sequence(
                bytes(0x30, 0x03, 0x80, 0x01, 0x00),
                bytes(0x04, 0x07, 0x91, 0x52, 0x76, 0x17, 0x32, 0x54, 0x76),
                bytes(0xa0, 0x0e, 0x80, 0x01, 0x01, 0xa4, 0x09,
                        0x80, 0x01, 0x0f, 0x82, 0x04, 0x67, 0x6d, 0x6c, 0x63),
                bytes(0x82, 0x08, 0x00, 0x01, 0x01, 0x21, 0x43, 0x65, (byte) 0x87, (byte) 0xf9),
                bytes(0xa7, 0x03, 0x80, 0x01, 0x3c)));
    }

    @Test
    void aProvideLocationAnswerDecodesTheCircleTheAgeTheCellAndTheAccuracyVerdict() {
        byte[] cell = bytes(0x00, (byte) 0xf1, 0x10, 0x04, (byte) 0xb1, 0x04, (byte) 0xd2);
        byte[] argument = Ber.sequence(
                Ber.octets(Gad.encodeCircle(0.3476, 32.5825, 45)),
                Ber.taggedInteger(Ber.context(0), 42),
                Ber.tlv(Ber.contextConstructed(6), Ber.tlv(Ber.context(0), cell)),
                Ber.nullValue(Ber.context(7)),
                Ber.taggedInteger(Ber.context(8), 0));

        MapLcs.ProvideSubscriberLocationResult result =
                MapLcs.parseProvideSubscriberLocation(argument);

        assertThat(result.circle().latitude()).isCloseTo(0.3476, within(3e-5));
        assertThat(result.ageMinutes()).isEqualTo(42);
        assertThat(result.cell().mcc()).isEqualTo("001");
        assertThat(result.cell().mnc()).isEqualTo("01");
        assertThat(result.cell().areaCode()).isEqualTo(1201);
        assertThat(result.cell().cellCode()).isEqualTo(1234);
        assertThat(result.cell().identity()).isEqualTo("001-01-1201-1234");
        assertThat(result.serviceArea()).isTrue();
        assertThat(result.accuracyFulfilled()).isTrue();
    }

    @Test
    void anAnswerWithoutAGeographicEstimateFailsRatherThanReturningACellAlone() {
        byte[] argument = Ber.sequence(Ber.taggedInteger(Ber.context(0), 5));

        assertThatThrownBy(() -> MapLcs.parseProvideSubscriberLocation(argument))
                .isInstanceOfSatisfying(MapException.class,
                        failure -> assertThat(failure.code()).isEqualTo("MAP_NO_LOCATION_ESTIMATE"));
    }

    @Test
    void anAnswerWithAnUnsupportedShapeIsRefusedByTheShapeCodec() {
        byte[] argument = Ber.sequence(Ber.octets(bytes(
                0x00, 0x00, 0x7e, (byte) 0x8e, 0x17, 0x2b, 0x76, 0x00)));

        assertThatThrownBy(() -> MapLcs.parseProvideSubscriberLocation(argument))
                .isInstanceOfSatisfying(MapException.class,
                        failure -> assertThat(failure.code()).isEqualTo("GAD_SHAPE"));
    }

    @Test
    void anAnyTimeEnquiryAsksForLocationAndOptionallyACurrentOne() {
        byte[] argument = MapLcs.anyTimeInterrogation(MSISDN, null, MSISDN, true, false);

        assertThat(argument).isEqualTo(Ber.sequence(
                bytes(0xa0, 0x09, 0x81, 0x07, 0x91, 0x52, 0x76, 0x17, 0x32, 0x54, 0x76),
                bytes(0xa1, 0x04, 0x80, 0x00, 0x83, 0x00),
                bytes(0x83, 0x07, 0x91, 0x52, 0x76, 0x17, 0x32, 0x54, 0x76)));
    }

    @Test
    void anAnyTimeAnswerDecodesTheCircuitSwitchedLocation() {
        byte[] cell = bytes(0x00, (byte) 0xf1, 0x10, 0x04, (byte) 0xb1, 0x04, (byte) 0xd2);
        byte[] locationInformation = Ber.tlv(Ber.contextConstructed(0), Ber.concat(
                Ber.integer(42),
                Ber.tlv(Ber.context(0), Gad.encodeCircle(0.3476, 32.5825, 45)),
                Ber.tlv(Ber.context(1), MapLcs.address("256772000001")),
                Ber.tlv(Ber.contextConstructed(3), Ber.tlv(Ber.context(0), cell)),
                Ber.tlv(Ber.context(6), MapLcs.address("256772000002")),
                Ber.nullValue(Ber.context(8)),
                Ber.nullValue(Ber.context(9))));
        byte[] argument = Ber.sequence(Ber.sequence(locationInformation));

        MapLcs.AnyTimeResult result = MapLcs.parseAnyTimeInterrogation(argument);

        assertThat(result.cs()).isNotNull();
        assertThat(result.cs().ageMinutes()).isEqualTo(42);
        assertThat(result.cs().circle().latitude()).isCloseTo(0.3476, within(3e-5));
        assertThat(result.cs().vlrNumber()).isEqualTo("256772000001");
        assertThat(result.cs().mscNumber()).isEqualTo("256772000002");
        assertThat(result.cs().cell().identity()).isEqualTo("001-01-1201-1234");
        assertThat(result.cs().serviceArea()).isTrue();
        assertThat(result.cs().currentLocationRetrieved()).isTrue();
        assertThat(result.ps()).isNull();
        assertThat(result.eps()).isNull();
    }

    @Test
    void anAnyTimeAnswerDecodesAnEpsCellWithItsTrackingArea() {
        byte[] eutranCgi = bytes(0x00, (byte) 0xf1, 0x10, 0x01, 0x23, 0x45, 0x67);
        byte[] trackingArea = bytes(0x00, (byte) 0xf1, 0x10, 0x04, (byte) 0xb1);
        byte[] eps = Ber.tlv(Ber.contextConstructed(13), Ber.concat(
                Ber.tlv(Ber.context(0), eutranCgi),
                Ber.tlv(Ber.context(1), trackingArea),
                Ber.nullValue(Ber.context(5)),
                Ber.taggedInteger(Ber.context(6), 5),
                Ber.tlv(Ber.context(7),
                        "mme1.epc.mnc001.mcc001.3gppnetwork.org".getBytes(StandardCharsets.US_ASCII))));
        byte[] argument = Ber.sequence(Ber.sequence(eps));

        MapLcs.AnyTimeResult result = MapLcs.parseAnyTimeInterrogation(argument);

        assertThat(result.eps()).isNotNull();
        assertThat(result.eps().cell().mcc()).isEqualTo("001");
        assertThat(result.eps().cell().mnc()).isEqualTo("01");
        assertThat(result.eps().cell().tac()).isEqualTo(1201);
        assertThat(result.eps().cell().cellCode()).isEqualTo(0x01234567L);
        assertThat(result.eps().ageMinutes()).isEqualTo(5);
        assertThat(result.eps().currentLocationRetrieved()).isTrue();
        assertThat(result.eps().mmeName()).isEqualTo("mme1.epc.mnc001.mcc001.3gppnetwork.org");
    }

    @Test
    void anAnyTimeAnswerWithoutSubscriberInformationIsAFailure() {
        assertThatThrownBy(() -> MapLcs.parseAnyTimeInterrogation(Ber.sequence()))
                .isInstanceOfSatisfying(MapException.class,
                        failure -> assertThat(failure.code()).isEqualTo("MAP_NO_SUBSCRIBER_INFO"));
    }

    @Test
    void aServingNodeNamedZeroDecodesAsTheDummyAddress() {
        byte[] argument = Ber.sequence(
                Ber.tlv(Ber.contextConstructed(1), Ber.octets(bytes(0x81, 0xf0))));

        assertThat(MapLcs.parseRoutingInfoForLcs(argument).networkNode()).isEqualTo("0");
    }

    @Test
    void theErrorTableNamesTheLcsFailuresAndKeepsUnknownCodesVisible() {
        assertThat(MapLcs.errorName(53)).isEqualTo("UNAUTHORIZED_LCS_CLIENT");
        assertThat(MapLcs.errorName(58)).isEqualTo("UNKNOWN_OR_UNREACHABLE_LCS_CLIENT");
        assertThat(MapLcs.errorName(27)).isEqualTo("ABSENT_SUBSCRIBER");
        assertThat(MapLcs.errorName(99)).isEqualTo("MAP_ERROR_99");
    }

    @Test
    void aTargetMustBeExactlyOneIdentityWithAValidLength() {
        assertThatThrownBy(() -> MapLcs.routingInfoForLcs(MSISDN, null, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("exactly one");
        assertThatThrownBy(() -> MapLcs.routingInfoForLcs(MSISDN, "0010101", MSISDN))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("exactly one");
        assertThatThrownBy(() -> MapLcs.routingInfoForLcs("1234", null, MSISDN))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("E.164");
        assertThatThrownBy(() -> MapLcs.provideSubscriberLocation(MSISDN, "12345", null, null, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("IMSI");
    }

    @Test
    void anOverlongClientNameIsRefusedRatherThanTruncated() {
        String name = "x".repeat(64);

        assertThatThrownBy(() -> MapLcs.provideSubscriberLocation(MSISDN, null, MSISDN, null, name))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("63");
    }

    private static byte[] bytes(int... values) {
        byte[] result = new byte[values.length];
        for (int i = 0; i < values.length; i++) {
            result[i] = (byte) values[i];
        }
        return result;
    }
}
