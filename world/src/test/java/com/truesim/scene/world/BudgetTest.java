package com.truesim.scene.world;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import com.truesim.common.jobs.TaskScheduler;
import com.truesim.common.state.ChangeClock;
import com.truesim.scene.core.SceneGraph;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/** Attach budgets, priorities and screen-space error. */
class BudgetTest {

    private static final Layer<String> TERRAIN = Layer.of("terrain", String.class, Levels.grid(10, 4));

    private final SceneGraph scene = new SceneGraph(new ChangeClock());
    private final TaskScheduler tasks = new TaskScheduler();
    private final World world = new World(scene, tasks);
    private final List<String> attached = new CopyOnWriteArrayList<>();
    private final List<String> discarded = new CopyOnWriteArrayList<>();

    BudgetTest() {
        world.register(TERRAIN, new CellLoader<>() {
            @Override
            public String load(Cell cell) {
                return cell.level() + ":" + cell.x();
            }

            @Override
            public void attach(String data, CellContent content) {
                attached.add(data);
            }

            @Override
            public void discard(String data) {
                discarded.add(data);
            }
        });
    }

    @AfterEach
    void stop() {
        world.close();
        tasks.close();
    }

    /** Drains until every requested cell has loaded or waits for the budget. */
    private void awaitLoaded(int cells) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (world.loadedCount() + world.waitingCount() < cells) {
            tasks.drainCompleted();
            assertThat(System.nanoTime()).isLessThan(deadline);
            Thread.sleep(1);
        }
    }

    @Test
    void attachingIsSpreadOverFramesInPriorityOrder() throws InterruptedException {
        world.setAttachBudget(2);
        world.update(); // a new frame
        world.request(TERRAIN, 0, 1, 0, 0, Priority.LOW);
        world.request(TERRAIN, 2, 1, 0, 0, Priority.LOW);
        world.request(TERRAIN, 0, 2, 0, 0, Priority.HIGH);
        world.request(TERRAIN, 3, 1, 0, 0, Priority.LOW);
        world.request(TERRAIN, 0, 3, 0, 0); // NORMAL
        world.request(TERRAIN, 0, 1, 0, 0, Priority.HIGH); // a second request raises cell 0:1 to HIGH
        world.request(TERRAIN, 0, 3, 0, 0, Priority.LOW); // a lower one does not lower it
        awaitLoaded(5);
        assertThat(world.loadedCount()).isEqualTo(2); // this frame's budget
        assertThat(world.waitingCount()).isEqualTo(3);
        List<String> first = List.copyOf(attached);
        attached.clear();
        world.update(); // next frame: two more, best first
        assertThat(world.loadedCount()).isEqualTo(4);
        world.update();
        assertThat(world.loadedCount()).isEqualTo(5);
        assertThat(world.waitingCount()).isZero();
        // HIGH first; among equals, coarser first.
        List<String> all = new ArrayList<>(first);
        all.addAll(attached);
        assertThat(all.subList(2, 5)).containsExactly(remaining(first));
        assertThat(world.state(TERRAIN, 0, 1, 0, 0)).isEqualTo(CellState.LOADED);
    }

    /** The order the waiting cells must attach in: HIGH ones, then NORMAL, then LOW coarsest first. */
    private static String[] remaining(List<String> first) {
        List<String> order = new ArrayList<>(List.of("0:1", "0:2", "0:3", "3:1", "2:1"));
        order.removeAll(first);
        return order.toArray(String[]::new);
    }

    @Test
    void cellsReleasedWhileWaitingAreDiscarded() throws InterruptedException {
        world.setAttachBudget(1);
        world.update();
        world.request(TERRAIN, 0, 1, 0, 0);
        world.request(TERRAIN, 0, 2, 0, 0);
        world.request(TERRAIN, 0, 3, 0, 0);
        awaitLoaded(3);
        assertThat(world.waitingCount()).isEqualTo(2);
        String waiting = attached.contains("0:1") ? "0:2" : "0:1";
        world.release(TERRAIN, 0, Integer.parseInt(waiting.substring(2)), 0, 0);
        assertThat(discarded).containsExactly(waiting);
        assertThat(world.waitingCount()).isEqualTo(1);
        world.update();
        assertThat(world.waitingCount()).isZero();
        assertThatIllegalArgumentException().isThrownBy(() -> world.setAttachBudget(0));
    }

    @Test
    void manyWaitingCellsAttachCoarsestFirst() throws InterruptedException {
        world.setAttachBudget(1);
        world.update();
        for (int i = 0; i < 24; i++) { // more than the queue holds at first
            world.request(TERRAIN, i % 4, i, 0, 0);
        }
        awaitLoaded(24);
        assertThat(world.waitingCount()).isEqualTo(23);
        world.release(TERRAIN, 1, 13, 0, 0); // from the middle of the queue (if it waits)
        world.release(TERRAIN, 2, 22, 0, 0);
        attached.clear();
        while (world.waitingCount() > 0) {
            world.update();
        }
        int previous = Integer.MAX_VALUE;
        for (String cell : attached) {
            int level = cell.charAt(0) - '0';
            assertThat(level).isLessThanOrEqualTo(previous); // coarsest first
            previous = level;
        }
    }

    @Test
    void screenErrorFollowsTheView() {
        InterestProfile profile = InterestProfile.builder()
            .layer(TERRAIN, Detail.screenError(100), Range.radius(400)).build(); // finest within about 190 m
        InterestSource camera = world.addInterest(profile, Priority.HIGH);
        camera.moveTo(5, 0, 5);
        camera.setView(Math.PI / 3, 1080);
        world.update();
        int wide = camera.cellCount(TERRAIN, 0);
        camera.setView(Math.PI / 3, 1080); // unchanged: nothing to do
        camera.setView(Math.PI / 12, 1080); // zoomed in: finer detail further out
        world.update();
        int zoomed = camera.cellCount(TERRAIN, 0);
        assertThat(zoomed).isGreaterThan(wide);
        camera.setView(Math.PI / 3, 270); // a small viewport needs less
        world.update();
        assertThat(camera.cellCount(TERRAIN, 0)).isLessThan(wide);
        assertThat(camera.cellCount(TERRAIN, 3)).isPositive(); // coarse cells out to the horizon
        assertThatIllegalArgumentException().isThrownBy(() -> camera.setView(0, 100));
        assertThatIllegalArgumentException().isThrownBy(() -> camera.setView(Math.PI, 100));
        assertThatIllegalArgumentException().isThrownBy(() -> camera.setView(1, 0));
    }

    @Test
    void screenErrorDetailIsChecked() {
        Detail detail = Detail.screenError(2);
        assertThat(detail.isScreenError()).isTrue();
        assertThat(detail.isFull()).isFalse();
        assertThat(detail.pixels()).isEqualTo(2);
        assertThat(detail).hasToString("screen error 2.0 px");
        assertThat(Detail.bands(5).isScreenError()).isFalse();
        assertThat(Detail.full().pixels()).isZero();
        assertThatIllegalArgumentException().isThrownBy(() -> Detail.screenError(0));
        assertThatIllegalArgumentException().isThrownBy(() -> Detail.screenError(Double.POSITIVE_INFINITY));
        assertThatIllegalArgumentException().isThrownBy(() -> InterestProfile.builder().layer(TERRAIN, detail))
            .withMessage("Screen-space error of layer terrain needs a range");
    }
}
