package com.flycode.teams;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class SharedTaskStoreTest {

    @TempDir
    Path tempDir;

    private SharedTaskStore newStore() {
        return new SharedTaskStore(tempDir.resolve("tasks.json"));
    }

    @Test
    void createAssignsIncrementingStringIdsAndPendingStatus() {
        var store = newStore();
        var t1 = store.create("first", "", "", null, null, "lead");
        var t2 = store.create("second", "desc", "alice", null, null, "lead");

        assertEquals("1", t1.id());
        assertEquals("2", t2.id());
        assertEquals("pending", t1.status());
        assertEquals("alice", t2.assignee());
        assertEquals("desc", t2.description());
        assertEquals("lead", t2.createdBy());
    }

    @Test
    void getReturnsTaskOrNull() {
        var store = newStore();
        var created = store.create("task", "", "", null, null, "");
        assertEquals("task", store.get(created.id()).title());
        assertNull(store.get("999"));
    }

    @Test
    void listFiltersByStatusAndAssignee() {
        var store = newStore();
        store.create("a", "", "alice", null, null, "");
        var b = store.create("b", "", "bob", null, null, "");
        store.update(b.id(), "completed", null, null, null, null);

        assertEquals(2, store.listTasks(null, null).size());
        assertEquals(1, store.listTasks("completed", null).size());
        assertEquals(1, store.listTasks(null, "alice").size());
        assertEquals(0, store.listTasks("completed", "alice").size());
    }

    @Test
    void updateChangesFieldsAndAppendsDependencies() {
        var store = newStore();
        var t = store.create("task", "", "", null, null, "");
        var updated = store.update(t.id(), "in_progress", "carol", "new desc",
                List.of("2"), List.of("3"));

        assertEquals("in_progress", updated.status());
        assertEquals("carol", updated.assignee());
        assertEquals("new desc", updated.description());
        assertEquals(List.of("2"), updated.blocks());
        assertEquals(List.of("3"), updated.blockedBy());

        // 重复追加应去重
        var again = store.update(t.id(), null, null, null, List.of("2"), null);
        assertEquals(List.of("2"), again.blocks());
    }

    @Test
    void updateReturnsNullForMissingTask() {
        assertNull(newStore().update("nope", "completed", null, null, null, null));
    }

    @Test
    void persistsAcrossInstancesAndReloadsLatest() {
        var store1 = newStore();
        store1.create("persisted", "", "", null, null, "lead");

        // 另一个实例（模拟队友进程）应能读到同一份数据
        var store2 = newStore();
        assertEquals(1, store2.listTasks(null, null).size());
        assertEquals("persisted", store2.get("1").title());

        // store2 写入后，store1 读操作前会 reload，能看到新任务
        store2.create("from-teammate", "", "", null, null, "bob");
        assertNotNull(store1.get("2"));
        assertEquals("from-teammate", store1.get("2").title());
    }

    @Test
    void initEmptyClearsAndResetsIds() throws Exception {
        var store = newStore();
        store.create("x", "", "", null, null, "");
        store.initEmpty();

        assertTrue(Files.exists(tempDir.resolve("tasks.json")));
        assertEquals(0, store.listTasks(null, null).size());
        // id 计数重置，下一条从 1 开始
        assertEquals("1", store.create("y", "", "", null, null, "").id());
    }
}
