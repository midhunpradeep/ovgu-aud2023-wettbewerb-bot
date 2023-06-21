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
    private static final int MIOJLNIR_DAMAGE = 35;

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

    private static int currentTurn = 0;

    @Override
    protected void executeTurn(GameState gameState, Controller controller) {
        currentTurn++;

        GameCharacter character = controller.getGameCharacter();

        List<Target> targets = new ArrayList<>();
        if (shouldHeal(character)) {
            targets = findHealthBoxes(gameState);
        }

        TargetDistanceComparator distanceComparator = new TargetDistanceComparator(character.getPlayerPos());
        targets.sort(distanceComparator);

        List<Target> enemies = findEnemies(gameState, character);
        enemies.sort(distanceComparator);
        enemies.sort(new TargetHealthComparator());

        targets.addAll(enemies);

        ShootInfo optimalTarget = null;

        boolean shouldTargetEnemies = true;

        for (Target target : targets) {
            if (!shouldTargetEnemies && target.isEnemy()) {
                break;
            }

            ShootInfo info = null;

            if (character.getWeapon(2).getAmmo() > 0 && target.isEnemy() &&
                    target.getEnemy().getHealth() <= MIOJLNIR_DAMAGE &&
                    target.getEnemy().getHealth() > PISTOL_DAMAGE
            ) {
                info = calculateMiojlnirShootInfo(gameState, controller, target);
            }

            if (info == null) {
                info = calculateShootInfo(gameState, controller, target);
            }

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
            controller.selectWeapon(optimalTarget.weaponType);
            controller.move(optimalTarget.movementOffset);
            controller.aim(optimalTarget.angle, optimalTarget.strength);
            controller.shoot();
        }
    }

    private ShootInfo calculateShootInfo(GameState gameState, Controller controller, Target target) {
        // should be greater than 1
        final int MAX_ITERATIONS = 25;

        Vector2 startPosition = controller.getGameCharacter().getPlayerPos();

        ShootInfo optimalInfo = null;

        for (int offset : getMovementOptions(gameState, startPosition)) {
            Vector2 position = new Vector2(startPosition).add(offset, 0);
            Vector2 t = target.getPosition().sub(position);

            if (t.x == 0) continue; // hacky fix for issues when target is right below you

            float minimumVelocity = (float) Math.sqrt(g * (t.y + Math.sqrt(t.y * t.y + t.x * t.x)));
            if (minimumVelocity > MAX_VELOCITY) continue;

            for (int i = 0; i < MAX_ITERATIONS; i++) {
                float v = MathUtils.lerp(minimumVelocity, MAX_VELOCITY, (float) i / (MAX_ITERATIONS - 1));

                float[] angles;

                if (i == 0) {
                    angles = new float[] { (float) Math.atan2(v * v, g * t.x) };
                } else {
                    angles = calculateAngles(v, t.x, t.y);
                }

                for (float angle : angles) {
                    int obstructions = numberOfObstructionsParabola(gameState, position, target, v, angle);

                    if (optimalInfo == null || obstructions < optimalInfo.obstructions) {
                        optimalInfo = new ShootInfo(
                                obstructions,
                                new Vector2((float) Math.cos(angle), (float) Math.sin(angle)),
                                v / MAX_VELOCITY,
                                offset,
                                WeaponType.WATER_PISTOL
                        );

                        if (optimalInfo.obstructions == 0) return optimalInfo;
                    }
                }
            }
        }

        return optimalInfo;
    }

    private ShootInfo calculateMiojlnirShootInfo(GameState gameState, Controller controller, Target target) {
        Vector2 startPosition = controller.getGameCharacter().getPlayerPos();

        for (int offset : getMovementOptions(gameState, startPosition)) {
            Vector2 position = new Vector2(startPosition).add(offset, 0);
            Vector2 t = target.getPosition().sub(position);

            float angle = (float) Math.atan2(t.y, t.x);
            if (numberOfObstructionsLine(gameState, position, target, MAX_VELOCITY, angle) > 0) continue;

            return new ShootInfo(
                    0,
                    new Vector2((float) Math.cos(angle), (float) Math.sin(angle)),
                    1,
                    offset,
                    WeaponType.MIOJLNIR
            );
        }

        return null;
    }

    private float[] calculateAngles(float v, float x, float y) {
        float delta = (float) Math.sqrt(Math.pow(v, 4) - g * (g * x * x + 2 * y * v * v));

        float high = (float) Math.atan2(v * v + delta, g * x);
        float low = (float) Math.atan2(v * v - delta, g * x);

        return new float[] { high, low };
    }

    private int numberOfObstructionsParabola(GameState gameState, Vector2 startPosition, Target target, float v, float angle) {
        final double DELTA_T = (double) 1 / 100;

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

    private int numberOfObstructionsLine(GameState gameState, Vector2 startPosition, Target target, float v, float angle) {
        final double DELTA_T = (double) 1 / 100;

        int tiles = 0;
        Tile lastTile = null;

        Vector2 targetPosition = target.getPosition();
        double timeToTarget = (targetPosition.x - startPosition.x) / (v * Math.cos(angle));

        for (double t = 0; t < timeToTarget; t += DELTA_T) {
            float x = (float) (startPosition.x + v * t * Math.cos(angle));
            float y = (float) (startPosition.y + v * t * Math.sin(angle));

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

    private List<Integer> getMovementOptions(GameState gameState, Vector2 characterPosition) {
        List<Integer> options = new ArrayList<>();
        options.add(0);

        IntVector2 characterTile = worldToTileCoords(characterPosition);

        boolean canMoveLeft = true;
        boolean canMoveRight = true;

        for (int offset = 1; (offset * 16) < 60 && (canMoveRight || canMoveLeft); offset++) {
            if (canMoveRight &&
                    isValidStandingPosition(gameState, new IntVector2(characterTile.x + offset, characterTile.y))) {
                options.add(offset * 16);
            } else {
                canMoveRight = false;
            }

            if (canMoveLeft &&
                    isValidStandingPosition(gameState, new IntVector2(characterTile.x - offset, characterTile.y))) {
                options.add(-offset * 16);
            } else {
                canMoveLeft = false;
            }
        }

        return options;
    }

    private boolean isValidStandingPosition(GameState gameState, IntVector2 tilePosition) {
        // tile blocked
        if (gameState.getTile(tilePosition) != null) return false;

        // nothing to stand on
        return gameState.getTile(tilePosition.x, tilePosition.y - 1) != null;
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

    private class TargetHealthComparator implements Comparator<Target> {
        @Override
        public int compare(Target t1, Target t2) {
            return Integer.compare(t1.getEnemy().getHealth(), t2.getEnemy().getHealth());
        }
    }

    private class ShootInfo {
        public final int obstructions;
        public final Vector2 angle;
        public final float strength;
        public final int movementOffset;
        public final WeaponType weaponType;

        public ShootInfo(int obstructions, Vector2 angle, float strength, int movementOffset, WeaponType weaponType) {
            this.obstructions = obstructions;
            this.angle = angle;
            this.strength = strength;
            this.movementOffset = movementOffset;
            this.weaponType = weaponType;
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
}
