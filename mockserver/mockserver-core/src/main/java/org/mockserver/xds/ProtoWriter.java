package org.mockserver.xds;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * Minimal protobuf wire-format writer supporting the subset needed for xDS
 * DiscoveryResponse encoding. Handles varint, length-delimited (string, bytes,
 * nested message), and repeated fields.
 * <p>
 * This avoids pulling in the full protobuf-java or grpc-java dependency.
 * Wire format reference: https://protobuf.dev/programming-guides/encoding/
 */
public class ProtoWriter {

    private static final int WIRE_TYPE_VARINT = 0;
    private static final int WIRE_TYPE_LENGTH_DELIMITED = 2;

    private final ByteArrayOutputStream buffer;

    public ProtoWriter() {
        this.buffer = new ByteArrayOutputStream(256);
    }

    public ProtoWriter(int initialCapacity) {
        this.buffer = new ByteArrayOutputStream(initialCapacity);
    }

    /**
     * Write a varint-encoded unsigned 64-bit integer (no field tag).
     */
    public void writeRawVarint(long value) {
        while ((value & ~0x7FL) != 0) {
            buffer.write((int) ((value & 0x7F) | 0x80));
            value >>>= 7;
        }
        buffer.write((int) value);
    }

    /**
     * Write a field tag (field number + wire type).
     */
    public void writeTag(int fieldNumber, int wireType) {
        writeRawVarint(((long) fieldNumber << 3) | wireType);
    }

    /**
     * Write a string field (field_number, wire_type=2, length, UTF-8 bytes).
     */
    public void writeString(int fieldNumber, String value) {
        if (value == null || value.isEmpty()) {
            return;
        }
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        writeTag(fieldNumber, WIRE_TYPE_LENGTH_DELIMITED);
        writeRawVarint(bytes.length);
        writeRawBytes(bytes);
    }

    /**
     * Write a bytes field (field_number, wire_type=2, length, raw bytes).
     */
    public void writeBytes(int fieldNumber, byte[] value) {
        if (value == null || value.length == 0) {
            return;
        }
        writeTag(fieldNumber, WIRE_TYPE_LENGTH_DELIMITED);
        writeRawVarint(value.length);
        writeRawBytes(value);
    }

    /**
     * Write a nested message field. The message bytes must already be serialized.
     */
    public void writeMessage(int fieldNumber, byte[] messageBytes) {
        writeBytes(fieldNumber, messageBytes);
    }

    /**
     * Write a uint32 varint field.
     */
    public void writeUInt32(int fieldNumber, int value) {
        if (value == 0) {
            return;
        }
        writeTag(fieldNumber, WIRE_TYPE_VARINT);
        writeRawVarint(Integer.toUnsignedLong(value));
    }

    /**
     * Write raw bytes directly to the output buffer.
     */
    public void writeRawBytes(byte[] bytes) {
        try {
            buffer.write(bytes);
        } catch (IOException e) {
            throw new RuntimeException("failed to write protobuf bytes", e);
        }
    }

    /**
     * Returns the serialized protobuf bytes.
     */
    public byte[] toByteArray() {
        return buffer.toByteArray();
    }

    /**
     * Returns the current size of the serialized data.
     */
    public int size() {
        return buffer.size();
    }

    /**
     * Reset the writer for reuse.
     */
    public void reset() {
        buffer.reset();
    }
}
