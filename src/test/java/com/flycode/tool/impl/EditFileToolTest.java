package com.flycode.tool.impl;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class EditFileToolTest {

    @Test
    void testSuccessfulEditReturnsColorableDiff(@TempDir Path tempDir) throws IOException {
        Path file = tempDir.resolve("utils.java");
        Files.writeString(file, "class Foo {\n    int x;\n}\n");

        var tool = new EditFileTool();
        var result = tool.execute(Map.of(
                "file_path", file.toString(),
                "old_string", "    int x;",
                "new_string", "    int x;\n    int y;"
        ));

        assertFalse(result.isError());
        assertTrue(result.output().startsWith("Updated"));
        assertTrue(result.output().contains("1 addition"));
        assertTrue(result.output().contains("0 removal"));
        assertTrue(result.output().contains("+"));

        // 确认文件确实被改写
        assertTrue(Files.readString(file).contains("int y;"));
    }

    @Test
    void testNonexistentFileIsError() throws IOException {
        var tool = new EditFileTool();
        var result = tool.execute(Map.of(
                "file_path", "/nonexistent/path.txt",
                "old_string", "a",
                "new_string", "b"
        ));
        assertTrue(result.isError());
    }
}
