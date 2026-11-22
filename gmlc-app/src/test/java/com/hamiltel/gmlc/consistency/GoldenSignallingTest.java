package com.hamiltel.gmlc.consistency;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hamiltel.gmlc.diameter.Avp;
import com.hamiltel.gmlc.diameter.DiameterMessage;
import com.hamiltel.gmlc.diameter.Tbcd;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The committed SLg frames in {@code fixtures/golden/slg.json} are the bytes a
 * peer sees. The gateway's codec must reproduce them octet for octet and read
 * back the values the fixture names; the Python core runs its own decoder over
 * the same file. A change to either codec that the fixture does not capture
 * fails here instead of showing up as a rejected request on the wire.
 */
class GoldenSignallingTest {

    private static final int FLAG_REQUEST = 0x80;
    private static final int FLAG_PROXIABLE = 0x40;

    @Test
    void everyGoldenFrameDecodesAndReEncodesByteForByte() throws Exception {
        JsonNode root = new ObjectMapper().readTree(
                Files.readString(RepoFiles.find("fixtures/golden/slg.json")));
        assertThat(root.get("vectors")).as("golden vectors").isNotEmpty();

        for (JsonNode vector : root.get("vectors")) {
            String name = vector.get("name").asText();
            byte[] wire = hex(vector.get("hex").asText());
            JsonNode expected = vector.get("message");

            DiameterMessage message = DiameterMessage.parse(wire);

            assertThat((long) message.commandCode()).as(name + " command").isEqualTo(expected.get("command_code").asLong());
            assertThat(message.applicationId()).as(name + " application").isEqualTo(expected.get("application_id").asLong());
            int flags = expected.get("flags").asInt();
            assertThat(message.request()).as(name + " R bit").isEqualTo((flags & FLAG_REQUEST) != 0);
            assertThat(message.proxiable()).as(name + " P bit").isEqualTo((flags & FLAG_PROXIABLE) != 0);
            assertThat(message.hopByHopId()).as(name + " hop-by-hop").isEqualTo(expected.get("hop_by_hop").asInt());
            assertThat(message.endToEndId()).as(name + " end-to-end").isEqualTo(expected.get("end_to_end").asInt());

            compareAvps(name, expected.get("avps"), message.avps());

            assertThat(message.encode()).as(name + " re-encoding").isEqualTo(wire);
        }
    }

    private static void compareAvps(String where, JsonNode expected, List<Avp> actual) {
        assertThat(actual.size()).as(where + " AVP count").isEqualTo(expected.size());
        for (int index = 0; index < expected.size(); index++) {
            JsonNode node = expected.get(index);
            Avp avp = actual.get(index);
            String at = where + " AVP " + node.get("code").asInt();

            assertThat(avp.code()).as(at + " code").isEqualTo(node.get("code").asInt());
            assertThat(avp.vendorId()).as(at + " vendor").isEqualTo(node.get("vendor_id").asLong());

            if (node.has("value_utf8")) {
                assertThat(avp.asText()).as(at + " text").isEqualTo(node.get("value_utf8").asText());
            }
            if (node.has("value_u32")) {
                assertThat(avp.asUnsigned32()).as(at + " Unsigned32").isEqualTo(node.get("value_u32").asLong());
            }
            if (node.has("value_hex")) {
                assertThat(hexOf(avp.copyOfValue())).as(at + " octets").isEqualTo(node.get("value_hex").asText());
            }
            if (node.has("value_tbcd")) {
                assertThat(Tbcd.decode(avp.copyOfValue())).as(at + " TBCD").isEqualTo(node.get("value_tbcd").asText());
            }
            if (node.has("children")) {
                compareAvps(at, node.get("children"), avp.children());
            }
        }
    }

    private static byte[] hex(String text) {
        assertThat(text.length() % 2).as("hex length").isZero();
        byte[] bytes = new byte[text.length() / 2];
        for (int at = 0; at < bytes.length; at++) {
            bytes[at] = (byte) Integer.parseInt(text.substring(at * 2, at * 2 + 2), 16);
        }
        return bytes;
    }

    private static String hexOf(byte[] bytes) {
        StringBuilder text = new StringBuilder(bytes.length * 2);
        for (byte value : bytes) {
            text.append(Character.forDigit((value >>> 4) & 0x0F, 16));
            text.append(Character.forDigit(value & 0x0F, 16));
        }
        return text.toString();
    }
}
