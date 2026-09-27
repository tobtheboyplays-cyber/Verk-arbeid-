from pathlib import Path
import shutil
root = Path(r"C:\Users\tobia\OneDrive\Documents\ChatGPT\MINECRAFT MOD\Verk-arbeid-\hearthstead-neoforge")
stage = root / "PROJECT_STATE" / "courier-route-diagnostics-20260912" / "proposed"
files = [
 "src/main/java/com/hearthstead/entity/ai/CourierWorkGoal.java",
 "src/main/java/com/hearthstead/gametest/GameTestFixtures.java",
 "src/main/java/com/hearthstead/gametest/LogisticsGameTests.java",
 "src/main/java/com/hearthstead/gametest/CourierWorkshopRouteGameTests.java",
 "src/main/java/com/hearthstead/gametest/MayorCourierGameTests.java",
]
for rel in files:
    target = stage / rel
    target.parent.mkdir(parents=True, exist_ok=True)
    shutil.copy2(root / rel, target)

def replace(rel, old, new):
    path = stage / rel
    text = path.read_text(encoding="utf-8")
    if old not in text:
        raise RuntimeError(f"anchor missing in {rel}: {old[:80]!r}")
    path.write_text(text.replace(old, new, 1), encoding="utf-8", newline="\n")

replace("src/main/java/com/hearthstead/entity/ai/CourierWorkGoal.java",
"""    public static boolean fuelJobIsHeld(UUID crafterId) {
        return RESERVATIONS.containsKey(RestockKey.forFuel(crafterId));
    }
}""",
"""    public static boolean fuelJobIsHeld(UUID crafterId) {
        return RESERVATIONS.containsKey(RestockKey.forFuel(crafterId));
    }

    /**
     * Read-only GameTest witness for the goal instance selected by vanilla's
     * scheduler. It exposes no route mutation and keeps the private mode
     * observable when a physical route times out.
     */
    public String diagnosticState() {
        return "mode=" + mode + ",job=" + job + ",done=" + done
            + ",source=" + sourcePos + ",dropOff=" + dropOff
            + ",craftDropOff=" + craftDropOff + ",transport=" + transportRequestId
            + ",equipment=" + equipmentRequestId + ",restock=" + reservationKey
            + ",workTicks=" + workTicks + ",cooldownUntil=" + cooldownUntil;
    }
}""")

replace("src/main/java/com/hearthstead/gametest/GameTestFixtures.java",
"""import com.hearthstead.block.PlaqueBlock;
import com.hearthstead.building.BuildingType;""",
"""import com.hearthstead.block.PlaqueBlock;
import com.hearthstead.building.BuildingType;
import com.hearthstead.entity.SettlerEntity;
import com.hearthstead.entity.ai.CourierWorkGoal;""")
replace("src/main/java/com/hearthstead/gametest/GameTestFixtures.java",
"""                + "mid-test (KF-021's side door)");
    }
}""",
"""                + "mid-test (KF-021's side door)");
    }

    /**
     * Timeout witness for Courier route tests. The scheduler owns whether a
     * goal is running, so report that exact owner beside the Courier's private
     * mode, physical navigation state, lifecycle and persisted contact
     * receipts. This is observation only; no session or route is repaired.
     */
    public static String courierRouteWitness(SettlerEntity courier) {
        StringBuilder running = new StringBuilder();
        StringBuilder courierGoals = new StringBuilder();
        courier.goalSelector.getAvailableGoals().forEach(wrapped -> {
            if (wrapped.isRunning()) {
                if (!running.isEmpty()) running.append('+');
                running.append(wrapped.getGoal().getClass().getSimpleName());
            }
            if (wrapped.getGoal() instanceof CourierWorkGoal goal) {
                if (!courierGoals.isEmpty()) courierGoals.append('+');
                courierGoals.append(wrapped.isRunning() ? "running/" : "idle/")
                    .append(goal.diagnosticState());
            }
        });
        var data = courier.getPersistentData();
        return "running=" + (running.isEmpty() ? "none" : running)
            + ",courierGoal=" + (courierGoals.isEmpty() ? "absent" : courierGoals)
            + ",lifecycle=" + courier.workerLifecycle().state()
            + ",task=" + courier.workerLifecycle().task()
            + ",navDone=" + courier.getNavigation().isDone()
            + ",navTarget=" + courier.getNavigation().getTargetPos()
            + ",sourceBag=" + data.get("HearthsteadCourierSourceBag")
            + ",foodBag=" + data.get("HearthsteadCourierFoodBag")
            + ",hearthBag=" + data.get("HearthsteadCourierHearthBag");
    }
}""")

replace("src/main/java/com/hearthstead/gametest/LogisticsGameTests.java",
"""                    + " [act=" + bud.getActivity()
                    + " lastRouteFailure=" + bud.routeFailureNote() + "]");""",
"""                    + " [act=" + bud.getActivity()
                    + " lastRouteFailure=" + bud.routeFailureNote()
                    + " routeDiag=" + GameTestFixtures.courierRouteWitness(bud) + "]");""")
replace("src/main/java/com/hearthstead/gametest/CourierWorkshopRouteGameTests.java",
"""                    + " navDone=" + bud.getNavigation().isDone()
                    + " navTarget=" + bud.getNavigation().getTargetPos() + "]");""",
"""                    + " navDone=" + bud.getNavigation().isDone()
                    + " navTarget=" + bud.getNavigation().getTargetPos()
                    + " routeDiag=" + GameTestFixtures.courierRouteWitness(bud) + "]");""")
replace("src/main/java/com/hearthstead/gametest/MayorCourierGameTests.java",
"""                    + ", position=" + f.mayor.blockPosition()
                    + ", container=" + f.mayor.placedWorkContainerPos()
                    + ", carryLoad=" + f.mayor.getCarryLoad() + "]");""",
"""                    + ", position=" + f.mayor.blockPosition()
                    + ", container=" + f.mayor.placedWorkContainerPos()
                    + ", carryLoad=" + f.mayor.getCarryLoad()
                    + ", routeDiag=" + GameTestFixtures.courierRouteWitness(f.mayor) + "]");""")


import subprocess
patch_chunks = []
for rel in files:
    actual = root / rel
    proposed = stage / rel
    proc = subprocess.run(
        ["git", "diff", "--no-index", "--binary", "--src-prefix=a/", "--dst-prefix=b/",
         "--", str(actual), str(proposed)],
        cwd=root, text=True, encoding="utf-8", stdout=subprocess.PIPE, stderr=subprocess.PIPE
    )
    if proc.returncode not in (0, 1):
        raise RuntimeError(proc.stderr)
    fixed = []
    for line in proc.stdout.splitlines():
        if line.startswith("diff --git "):
            fixed.append(f"diff --git a/{rel} b/{rel}")
        elif line.startswith("--- "):
            fixed.append(f"--- a/{rel}")
        elif line.startswith("+++ "):
            fixed.append(f"+++ b/{rel}")
        else:
            fixed.append(line)
    patch_chunks.append("\n".join(fixed))
(root / "PROJECT_STATE" / "courier-route-diagnostics-20260912" / "courier-route-diagnostics.patch").write_text(
    "\n".join(patch_chunks) + "\n", encoding="utf-8", newline="\n")

