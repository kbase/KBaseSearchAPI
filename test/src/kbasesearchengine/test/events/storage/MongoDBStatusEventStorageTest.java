package kbasesearchengine.test.events.storage;

import static org.hamcrest.CoreMatchers.is;
import static org.junit.Assert.assertThat;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.fail;
import static kbasesearchengine.test.common.TestCommon.set;

import java.nio.file.Paths;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;

import org.bson.Document;
import org.bson.types.ObjectId;
import org.junit.AfterClass;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;

import com.google.common.base.Optional;
import com.google.common.collect.Range;
import com.mongodb.MongoClient;
import com.mongodb.client.MongoDatabase;

import kbasesearchengine.events.StatusEvent;
import kbasesearchengine.events.StatusEventID;
import kbasesearchengine.events.StatusEventProcessingState;
import kbasesearchengine.events.StatusEventType;
import kbasesearchengine.events.StoredStatusEvent;
import kbasesearchengine.events.exceptions.RetriableIndexingException;
import kbasesearchengine.events.storage.MongoDBStatusEventStorage;
import kbasesearchengine.events.storage.StatusEventStorage;
import kbasesearchengine.system.StorageObjectType;
import kbasesearchengine.test.common.TestCommon;
import us.kbase.common.test.controllers.mongo.MongoController;

public class MongoDBStatusEventStorageTest {

    private StatusEventStorage storage;
    private static MongoController mongo;
    private static MongoDatabase db;
    private static MongoClient mc;

    @BeforeClass
    public static void setUpClass() throws Exception {
        TestCommon.stfuLoggers();
        mongo = new MongoController(
                TestCommon.getMongoExe(),
                Paths.get(TestCommon.getTempDir()),
                TestCommon.useWiredTigerEngine());
        mc = new MongoClient("localhost:" + mongo.getServerPort());
        db = mc.getDatabase("test_mongostorage");
    }
    
    @AfterClass
    public static void tearDownClass() throws Exception {
        if (mc != null) {
            mc.close();
        }
        if (mongo != null) {
            mongo.destroy(TestCommon.getDeleteTempFiles());
        }
    }
    
    @Before
    public void init() throws Exception {
        TestCommon.destroyDB(db);
        storage  = new MongoDBStatusEventStorage(db);
    }

    @Test
    public void storeAndGetNoTypeAllFields() throws Exception {
        // tests with all possible fields in status event
        final StoredStatusEvent sse = storage.store(StatusEvent.getBuilder(
                "WS", Instant.ofEpochMilli(10000), StatusEventType.COPY_ACCESS_GROUP)
                .withNullableAccessGroupID(6)
                .withNullableisPublic(true)
                .withNullableNewName("foo")
                .withNullableObjectID("bar")
                .withNullableVersion(7)
                .build(),
                StatusEventProcessingState.UNPROC);
        
        assertThat("incorrect state", sse.getState(), is(StatusEventProcessingState.UNPROC));
        assertThat("incorrect event", sse.getEvent(), is(StatusEvent.getBuilder(
                "WS", Instant.ofEpochMilli(10000), StatusEventType.COPY_ACCESS_GROUP)
                .withNullableAccessGroupID(6)
                .withNullableisPublic(true)
                .withNullableNewName("foo")
                .withNullableObjectID("bar")
                .withNullableVersion(7)
                .build()));
        assertNotNull("id is null", sse.getId());
        
        final StoredStatusEvent got = storage.get(sse.getId()).get();
        
        assertThat("ids don't match", got.getId(), is(sse.getId()));
        assertThat("incorrect state", got.getState(), is(StatusEventProcessingState.UNPROC));
        assertThat("incorrect event", got.getEvent(), is(StatusEvent.getBuilder(
                "WS", Instant.ofEpochMilli(10000), StatusEventType.COPY_ACCESS_GROUP)
                .withNullableAccessGroupID(6)
                .withNullableisPublic(true)
                .withNullableNewName("foo")
                .withNullableObjectID("bar")
                .withNullableVersion(7)
                .build()));
    }
    
    @Test
    public void storeAndGetWithTypeMinimalFields() throws Exception {
        // tests with minimal possible fields in statusevent
        
        final StorageObjectType sot = new StorageObjectType("foo", "bar", 9);
        final StoredStatusEvent sse = storage.store(StatusEvent.getBuilder(
                sot, Instant.ofEpochMilli(20000), StatusEventType.DELETE_ALL_VERSIONS).build(),
                StatusEventProcessingState.FAIL);
        
        assertThat("incorrect state", sse.getState(), is(StatusEventProcessingState.FAIL));
        assertThat("incorrect event", sse.getEvent(), is(StatusEvent.getBuilder(
                new StorageObjectType("foo", "bar", 9), Instant.ofEpochMilli(20000),
                    StatusEventType.DELETE_ALL_VERSIONS)
                .build()));
        assertNotNull("id is null", sse.getId());
        
        final StoredStatusEvent got = storage.get(sse.getId()).get();
        
        assertThat("ids don't match", got.getId(), is(sse.getId()));
        assertThat("incorrect state", got.getState(), is(StatusEventProcessingState.FAIL));
        assertThat("incorrect event", got.getEvent(), is(StatusEvent.getBuilder(
                new StorageObjectType("foo", "bar", 9), Instant.ofEpochMilli(20000),
                StatusEventType.DELETE_ALL_VERSIONS).build()));
    }
    
    @Test
    public void storeAndGetNonExistant() throws Exception {
        
        final StorageObjectType sot = new StorageObjectType("foo", "bar", 9);
        final StoredStatusEvent sse = storage.store(StatusEvent.getBuilder(
                sot, Instant.ofEpochMilli(20000), StatusEventType.DELETE_ALL_VERSIONS).build(),
                StatusEventProcessingState.FAIL);
        
        assertThat("incorrect state", sse.getState(), is(StatusEventProcessingState.FAIL));
        assertThat("incorrect event", sse.getEvent(), is(StatusEvent.getBuilder(
                new StorageObjectType("foo", "bar", 9), Instant.ofEpochMilli(20000),
                    StatusEventType.DELETE_ALL_VERSIONS)
                .build()));
        assertNotNull("id is null", sse.getId());
        
        final Optional<StoredStatusEvent> got = storage.get(
                new StatusEventID(new ObjectId().toString()));
        assertThat("expected absent", got, is(Optional.absent()));
    }
    
    @Test
    public void mark() throws Exception {
        final StoredStatusEvent sse = storage.store(StatusEvent.getBuilder(
                "KE", Instant.ofEpochMilli(30000), StatusEventType.COPY_ACCESS_GROUP).build(),
                StatusEventProcessingState.UNPROC);
        
        final StoredStatusEvent marked = storage.setProcessingState(sse, StatusEventProcessingState.FAIL).get();
        assertThat("ids don't match", marked.getId(), is(sse.getId()));
        assertThat("incorrect state", marked.getState(), is(StatusEventProcessingState.FAIL));
        assertThat("incorrect event", marked.getEvent(), is(StatusEvent.getBuilder(
                "KE", Instant.ofEpochMilli(30000), StatusEventType.COPY_ACCESS_GROUP).build()));
        
        final StoredStatusEvent got = storage.get(sse.getId()).get();
        assertThat("ids don't match", got.getId(), is(sse.getId()));
        assertThat("incorrect state", got.getState(), is(StatusEventProcessingState.FAIL));
        assertThat("incorrect event", got.getEvent(), is(StatusEvent.getBuilder(
                "KE", Instant.ofEpochMilli(30000), StatusEventType.COPY_ACCESS_GROUP).build()));
    }
    
    @Test
    public void markNonExistant() throws Exception {
        final StoredStatusEvent sse = storage.store(StatusEvent.getBuilder(
                "FE", Instant.ofEpochMilli(30000), StatusEventType.COPY_ACCESS_GROUP).build(),
                StatusEventProcessingState.UNPROC);
        
        final Optional<StoredStatusEvent> marked = storage.setProcessingState(
                new StoredStatusEvent(sse.getEvent(),
                        new StatusEventID(new ObjectId().toString()),
                        sse.getState()),
                StatusEventProcessingState.FAIL);
        
        assertThat("expected absent", marked, is(Optional.absent()));
    }
    
    @Test
    public void getByState() throws Exception {
        store(1100, StatusEventProcessingState.UNPROC);
        store(10, StatusEventProcessingState.UNINDX);
        store(10, StatusEventProcessingState.FAIL);
        
        //basic checks
        assertFound(StatusEventProcessingState.UNPROC, 15, Range.closed(1, 15));
        assertFound(StatusEventProcessingState.UNINDX, 15, Range.closed(1, 10));
        assertFound(StatusEventProcessingState.FAIL, 15, Range.closed(1, 10));
        // ensures no events are found for INDX
        assertFound(StatusEventProcessingState.INDX, 15, Range.closed(-1, -1));
        
        // check limit works as expected
        assertFound(StatusEventProcessingState.UNPROC, 0, Range.closed(1, 1000));
        assertFound(StatusEventProcessingState.UNPROC, 1, Range.closed(1, 1));
        assertFound(StatusEventProcessingState.UNPROC, 999, Range.closed(1, 999));
        assertFound(StatusEventProcessingState.UNPROC, 1000, Range.closed(1, 1000));
        assertFound(StatusEventProcessingState.UNPROC, 1001, Range.closed(1, 1000));
        
        // check changing state moves limits around
        setState(StatusEventProcessingState.UNPROC, StatusEventProcessingState.INDX,
                Range.closed(100, 150));
        
        assertFound(StatusEventProcessingState.INDX, 25, Range.closed(100, 125));
        assertFound(StatusEventProcessingState.INDX, 51, Range.closed(100, 150));
        assertFound(StatusEventProcessingState.INDX, -1, Range.closed(100, 150));
        assertFound(StatusEventProcessingState.UNPROC, -1,
                Range.closed(1, 99), Range.closed(151, 1051));
    }

    private void setState(
            final StatusEventProcessingState oldState,
            final StatusEventProcessingState newState,
            final Range<Integer> timesToModifyInSec)
            throws Exception {
        for (final StoredStatusEvent event: storage.get(oldState, -1)) {
            final int t = (int) event.getEvent().getTimestamp().toEpochMilli();
            if (timesToModifyInSec.contains(t / 1000)) {
                storage.setProcessingState(event, newState);
            }
        }
    }

    @SafeVarargs
    private final void assertFound(
            final StatusEventProcessingState state,
            final int limit,
            final Range<Integer>... ranges)
            throws Exception {
        for (final StoredStatusEvent event: storage.get(state, limit)) {
            assertInRange(event.getEvent().getTimestamp(), ranges);
        }
    }

    private void assertInRange(final Instant timestamp, final Range<Integer>[] ranges) {
        final int t = (int) timestamp.toEpochMilli();
        for (final Range<Integer> range: ranges) {
            if (range.contains(t / 1000)) {
                return;
            }
        }
        fail(String.format("Time %s not in any ranges %s", t / 1000, Arrays.asList(ranges)));
    }

    private void store(final int count, final StatusEventProcessingState state)
            throws RetriableIndexingException {
        final List<Integer> times = new ArrayList<>();
        for (int i = 1; i <= count; i++) {
            times.add(i);
        }
        Collections.shuffle(times);
        for (final int time: times) {
            storage.store(StatusEvent.getBuilder(
                    "foo", Instant.ofEpochMilli(time * 1000), StatusEventType.NEW_VERSION).build(),
                    state);
        }
    }
    
    @Test
    public void constructFail() {
        try {
            new MongoDBStatusEventStorage(null);
            fail("expected exception");
        } catch (Exception got) {
            TestCommon.assertExceptionCorrect(got, new NullPointerException("db"));
        }
    }
    
    @Test
    public void storeFail() {
        failStore(null, StatusEventProcessingState.UNINDX, new NullPointerException("newEvent"));
        final StatusEvent event = StatusEvent.getBuilder(
                "Ws", Instant.ofEpochMilli(10000), StatusEventType.NEW_ALL_VERSIONS).build();
        failStore(event, null, new NullPointerException("state"));
    }
    
    private void failStore(
            final StatusEvent event,
            final StatusEventProcessingState state,
            final Exception expected) {
        try {
            storage.store(event, state);
            fail("expected exception");
        } catch (Exception got) {
            TestCommon.assertExceptionCorrect(got, expected);
        }
    }
    
    @Test
    public void getFail() {
        try {
            storage.get(null);
            fail("expected exception");
        } catch (Exception got) {
            TestCommon.assertExceptionCorrect(got, new NullPointerException("id"));
        }
    }
    
    @Test
    public void getByStateFail() {
        try {
            storage.get(null, -1);
            fail("expected exception");
        } catch (Exception got) {
            TestCommon.assertExceptionCorrect(got, new NullPointerException("state"));
        }
    }
    
    @Test
    public void setStateFail() {
        final StoredStatusEvent sse = new StoredStatusEvent(StatusEvent.getBuilder(
                "ws", Instant.ofEpochMilli(10000), StatusEventType.NEW_VERSION).build(),
                new StatusEventID("foo"), StatusEventProcessingState.UNINDX);
        failSetState(null, StatusEventProcessingState.INDX, new NullPointerException("event"));
        failSetState(sse, null, new NullPointerException("state"));
    }
    
    private void failSetState(
            final StoredStatusEvent event,
            final StatusEventProcessingState state,
            final Exception expected) {
        try {
            storage.setProcessingState(event, state);
            fail("expected exception");
        } catch (Exception got) {
            TestCommon.assertExceptionCorrect(got, expected);
        }
    }
    
    @Test
    public void indexes() {
        final Set<Document> indexes = new HashSet<>();
        // this is annoying. MongoIterator has two forEach methods with different signatures
        // and so which one to call is ambiguous for lambda expressions.
        db.getCollection("searchEvents").listIndexes().forEach((Consumer<Document>) indexes::add);
        for (final Document d: indexes) {
            d.remove("v"); // remove the mongo index version which is no business of ours
        }
        assertThat("incorrect indexes", indexes, is(set(
                new Document()
                        .append("key", new Document("status", 1).append("time", 1))
                        .append("name", "status_1_time_1")
                        .append("ns", "test_mongostorage.searchEvents"),
                new Document()
                        .append("key", new Document("_id", 1))
                        .append("name", "_id_")
                        .append("ns", "test_mongostorage.searchEvents")
                )));
    }
}
