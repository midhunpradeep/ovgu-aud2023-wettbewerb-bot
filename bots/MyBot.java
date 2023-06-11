package bots;

import com.badlogic.gdx.math.MathUtils;
import com.badlogic.gdx.math.Vector2;
import com.gats.manager.Bot;
import com.gats.manager.Controller;
import com.gats.simulation.*;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

public class MyBot extends Bot {
    private static final float g = 9.81f * 16;
    private static final float MAX_VELOCITY = 400;
    private static final int HEAL_AMOUNT = 35;
    private static final int PISTOL_DAMAGE = 10;

    private static int turnCounter = 0;

    @Override
    public String getStudentName() {
        return "Midhun Pradeep Nair";
    }

    @Override
    public int getMatrikel() {
        return 241691;
    }

    @Override
    public String getName() {
        return "Obi-Wan Catnobi";
    }

    @Override
    protected void init(GameState gameState) {
    }

    @Override
    protected void executeTurn(GameState gameState, Controller controller) {
        turnCounter++;

        GameCharacter character = controller.getGameCharacter();

        List<Target> targets = new ArrayList<>();
        TargetDistanceComparator comparator = new TargetDistanceComparator(character.getPlayerPos());

        if (shouldHeal(character)) {
            targets = findHealthBoxes(gameState);
        }

        targets.sort(comparator);

        List<Target> enemies = findEnemies(gameState, character);
        enemies.sort(comparator);

        targets.addAll(enemies);

        controller.selectWeapon(WeaponType.WATER_PISTOL);

        ShootInfo optimalTarget = null;

        boolean shouldTargetEnemies = true;

        for (Target target : targets) {
            if (!shouldTargetEnemies && target.isEnemy()) {
                break;
            }

            ShootInfo info = calculateShootInfo(gameState, controller, target);

            if (info == null) continue;

            // if bot can gain health, don't shoot enemies
            if (target.isTile()) {
                assert target.getTile().getTileType() == Tile.TileType.HEALTH_BOX;

                int expectedDamage = PISTOL_DAMAGE * info.obstructions;
                if (expectedDamage >= character.getHealth()) {
                    continue;
                }

                int expectedHealAmount = HEAL_AMOUNT - expectedDamage;
                if (expectedHealAmount > 0) {
                    shouldTargetEnemies = false;
                }
            }

            if (optimalTarget == null || info.obstructions < optimalTarget.obstructions) {
                optimalTarget = info;
            }

            if (optimalTarget.obstructions == 0) {
                break;
            }
        }

        if (optimalTarget != null) {
            controller.aim(optimalTarget.angle, optimalTarget.strength);
            controller.shoot();
        }

    }

    private ShootInfo calculateShootInfo(GameState gameState, Controller controller, Target target) {
        // should be greater than 1
        final int MAX_ITERATIONS = 200;

        Vector2 startPosition = controller.getGameCharacter().getPlayerPos();

        Vector2 t = target.getPosition().sub(startPosition);

        float minimumVelocity = (float) Math.sqrt(g * (t.y + Math.sqrt(t.y * t.y + t.x * t.x)));

        if (minimumVelocity > MAX_VELOCITY) return null;

        float optimalVelocity = 0;
        float optimalAngle = 0;
        int minimumObstructions = 100_000;

        OUTER:
        for (int i = 0; i < MAX_ITERATIONS; i++) {
            float v = MathUtils.lerp(minimumVelocity, MAX_VELOCITY, (float) i / (MAX_ITERATIONS - 1));

            float[] angles;

            if (i == 0) {
                angles = new float[] { (float) Math.atan2(v * v, g * t.x) };
            } else {
                angles = calculateAngles(v, t.x, t.y);
            }

            for (float angle : angles) {
                int obstructions = numberOfObstructions(gameState, startPosition, target, v, angle);

                if (obstructions < minimumObstructions) {
                    optimalVelocity = v;
                    optimalAngle = angle;
                    minimumObstructions = obstructions;
                }

                if (obstructions == 0) break OUTER;
            }
        }

        Vector2 angleV = new Vector2((float) Math.cos(optimalAngle), (float) Math.sin(optimalAngle));
        float strength = optimalVelocity / MAX_VELOCITY;

        return new ShootInfo(minimumObstructions, angleV, strength);
    }

    private float[] calculateAngles(float v, float x, float y) {
        float delta = (float) Math.sqrt(Math.pow(v, 4) - g * (g * x * x + 2 * y * v * v));

        float high = (float) Math.atan2(v * v + delta, g * x);
        float low = (float) Math.atan2(v * v - delta, g * x);

        return new float[] { high, low };
    }

    private int numberOfObstructions(GameState gameState, Vector2 startPosition, Target target, float v, float angle) {
        final double DELTA_T = (double) 1 / 20;

        int tiles = 0;
        Tile lastTile = null;

        Vector2 targetPosition = target.getPosition();
        double timeToTarget = (targetPosition.x - startPosition.x) / (v * Math.cos(angle));

        for (double t = 0; t < timeToTarget; t += DELTA_T) {
            float x = (float) (startPosition.x + v * t * Math.cos(angle));
            float y = (float) (startPosition.y + v * t * Math.sin(angle) - (0.5) * g * t * t);

            Tile tile = gameState.getTile(worldToTileCoords(new Vector2(x, y)));

            if (tile != null && (lastTile == null || !lastTile.equals(tile))) {
                if (target.isTile() && tile.equals(target.getTile())) {
                    break;
                }

                lastTile = tile;
                tiles++;
            }
        }

        return tiles;
    }

    private IntVector2 worldToTileCoords(Vector2 worldCoords) {
        return new IntVector2((int) (worldCoords.x / 16), (int) (worldCoords.y / 16));
    }

    private List<Target> findEnemies(GameState gameState, GameCharacter character) {
        List<Target> enemies = new ArrayList<>();

        for (int team = 0; team < gameState.getTeamCount(); team++) {
            if (team == character.getTeam()) continue;

            for (int c = 0; c < gameState.getCharactersPerTeam(); c++) {
                GameCharacter enemy = gameState.getCharacterFromTeams(team, c);
                if (!enemy.isAlive()) continue;

                enemies.add(new Target(enemy));
            }
        }

        return enemies;
    }

    private List<Target> findHealthBoxes(GameState gameState) {
        List<Target> boxes = new ArrayList<>();

        for (int x = 0; x < gameState.getBoardSizeX(); x++) {
            for (int y = 0; y < gameState.getBoardSizeY(); y++) {
                Tile tile = gameState.getTile(x, y);
                if (tile != null && tile.getTileType() == Tile.TileType.HEALTH_BOX) {
                    boxes.add(new Target(tile));
                }
            }
        }

        return boxes;
    }

    private boolean shouldHeal(GameCharacter character) {
        return character.getHealth() <= (100 - HEAL_AMOUNT);
    }

    private class TargetDistanceComparator implements Comparator<Target> {
        private final Vector2 origin;

        public TargetDistanceComparator(Vector2 origin) {
            this.origin = origin;
        }

        @Override
        public int compare(Target v1, Target v2) {
            return Float.compare(v1.getPosition().dst(origin), v2.getPosition().dst(origin));
        }
    }

    private class ShootInfo {
        public final int obstructions;
        public final Vector2 angle;
        public final float strength;

        public ShootInfo(int obstructions, Vector2 angle, float strength) {
            this.obstructions = obstructions;
            this.angle = angle;
            this.strength = strength;
        }
    }

    private class Target {
        private GameCharacter enemy;
        private Tile tile;

        public Target(GameCharacter enemy) {
            this.enemy = enemy;
            this.tile = null;
        }

        public Target(Tile tile) {
            this.tile = tile;
            this.enemy = null;
        }

        public GameCharacter getEnemy() {
            return enemy;
        }

        public void setEnemy(GameCharacter enemy) {
            this.enemy = enemy;
            this.tile = null;
        }

        public Tile getTile() {
            return tile;
        }

        public void setTile(Tile tile) {
            this.tile = tile;
            this.enemy = null;
        }

        public boolean isTile() {
            return tile != null;
        }

        public boolean isEnemy() {
            return enemy != null;
        }

        public Vector2 getPosition() {
            if (enemy == null) {
                return tile.getWorldPosition();
            } else {
                return enemy.getPlayerPos();
            }
        }
    }

    private class BounceInfo {
    }
}
