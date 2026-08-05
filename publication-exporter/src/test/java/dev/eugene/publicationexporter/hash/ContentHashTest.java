package dev.eugene.publicationexporter.hash;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ContentHashTest {

    @Test
    void sha256HexProducesTheKnownDigestOfAnEmptyString() {
        assertEquals(
                "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
                ContentHash.sha256Hex(""));
    }

    @Test
    void sha256HexProducesTheKnownDigestOfAbc() {
        assertEquals(
                "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
                ContentHash.sha256Hex("abc"));
    }

    @Test
    void sha256HexIsDeterministic() {
        assertEquals(ContentHash.sha256Hex("same content"), ContentHash.sha256Hex("same content"));
    }
}
