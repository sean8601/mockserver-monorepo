package org.mockserver.xds;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Minimal protobuf wire-format reader supporting the subset needed for xDS
 * DiscoveryRequest decoding. Handles varint, length-delimited (string, bytes,
 * nested message), and repeated fields.
 * <p>
 * This avoids pulling in the full protobuf-java or grpc-java dependency.
 * Wire format reference: https://protobuf.dev/programming-guides/encoding/
 */
public class ProtoReader {

    public static final int WIRE_TYPE_VARINT = 0;
    public static final int WIRE_TYPE_64BIT = 1;
    public static final int WIRE_TYPE_LENGTH_DELIMITED = 2;
    public static final int WIRE_TYPE_32BIT = 5;

    private final byte[] data;
    private int pos;
    private final int limit;

    public ProtoReader(byte[] data) {
        this(data, 0, data.length);
    }

    public ProtoReader(byte[] data, int offset, int length) {
        this.data = data;
        this.pos = offset;
        this.limit = offset + length;
    }

    public boolean hasRemaining() {
        return pos < limit;
    }

    /**
     * Read a varint (up to 64 bits).
     */
    public long readVarint() {
        long result = 0;
        int shift = 0;
        while (pos < limit) {
            byte b = data[pos++];
            result |= (long) (b & 0x7F) << shift;
            if ((b & 0x80) == 0) {
                return result;
            }
            shift += 7;
            if (shift >= 64) {
                throw new IllegalStateException("varint too long at position " + pos);
            }
        }
        throw new IllegalStateException("truncated varint at position " + pos);
    }

    /**
     * Read a field tag and return {fieldNumber, wireType}.
     */
    public int[] readTag() {
        long tag = readVarint();
        int fieldNumber = (int) (tag >>> 3);
        int wireType = (int) (tag & 0x07);
        return new int[]{fieldNumber, wireType};
    }

    /**
     * Read a length-delimited field as raw bytes.
     */
    public byte[] readLengthDelimited() {
        int length = (int) readVarint();
        if (length < 0 || pos + length > limit) {
            throw new IllegalStateException("invalid length-delimited field: length=" + length + " at position " + pos);
        }
        byte[] result = Arrays.copyOfRange(data, pos, pos + length);
        pos += length;
        return result;
    }

    /**
     * Read a length-delimited field as a UTF-8 string.
     */
    public String readString() {
        byte[] bytes = readLengthDelimited();
        return new String(bytes, StandardCharsets.UTF_8);
    }

    /**
     * Skip a field of the given wire type. Validates bounds for all fixed-width
     * and length-delimited skips to prevent out-of-bounds reads from malformed
     * or truncated input (hardening against untrusted gRPC clients).
     */
    public void skipField(int wireType) {
        switch (wireType) {
            case WIRE_TYPE_VARINT:
                readVarint();
                break;
            case WIRE_TYPE_64BIT:
                if (pos + 8 > limit) {
                    throw new IllegalStateException("truncated 64-bit field at position " + pos + " (limit=" + limit + ")");
                }
                pos += 8;
                break;
            case WIRE_TYPE_LENGTH_DELIMITED:
                int length = (int) readVarint();
                if (length < 0 || pos + length > limit) {
                    throw new IllegalStateException("invalid length-delimited skip: length=" + length + " at position " + pos + " (limit=" + limit + ")");
                }
                pos += length;
                break;
            case WIRE_TYPE_32BIT:
                if (pos + 4 > limit) {
                    throw new IllegalStateException("truncated 32-bit field at position " + pos + " (limit=" + limit + ")");
                }
                pos += 4;
                break;
            default:
                throw new IllegalStateException("unknown wire type: " + wireType);
        }
    }

    /**
     * Utility: parse all string values for a given field number from a message.
     * Returns a list of strings found for that field (for repeated string fields).
     */
    public static List<String> readRepeatedString(byte[] messageBytes, int targetFieldNumber) {
        List<String> result = new ArrayList<>();
        ProtoReader reader = new ProtoReader(messageBytes);
        while (reader.hasRemaining()) {
            int[] tag = reader.readTag();
            int fieldNumber = tag[0];
            int wireType = tag[1];
            if (fieldNumber == targetFieldNumber && wireType == WIRE_TYPE_LENGTH_DELIMITED) {
                result.add(reader.readString());
            } else {
                reader.skipField(wireType);
            }
        }
        return result;
    }

    /**
     * Utility: read the first string value for a given field number from a message.
     * Returns null if the field is not found.
     */
    public static String readFirstString(byte[] messageBytes, int targetFieldNumber) {
        ProtoReader reader = new ProtoReader(messageBytes);
        while (reader.hasRemaining()) {
            int[] tag = reader.readTag();
            int fieldNumber = tag[0];
            int wireType = tag[1];
            if (fieldNumber == targetFieldNumber && wireType == WIRE_TYPE_LENGTH_DELIMITED) {
                return reader.readString();
            } else {
                reader.skipField(wireType);
            }
        }
        return null;
    }

    /**
     * Utility: read all length-delimited (bytes/message) values for a given field number.
     */
    public static List<byte[]> readRepeatedBytes(byte[] messageBytes, int targetFieldNumber) {
        List<byte[]> result = new ArrayList<>();
        ProtoReader reader = new ProtoReader(messageBytes);
        while (reader.hasRemaining()) {
            int[] tag = reader.readTag();
            int fieldNumber = tag[0];
            int wireType = tag[1];
            if (fieldNumber == targetFieldNumber && wireType == WIRE_TYPE_LENGTH_DELIMITED) {
                result.add(reader.readLengthDelimited());
            } else {
                reader.skipField(wireType);
            }
        }
        return result;
    }
}
