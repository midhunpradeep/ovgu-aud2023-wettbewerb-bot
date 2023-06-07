package bots;

import com.badlogic.gdx.math.MathUtils;
import com.badlogic.gdx.math.Vector2;
import com.gats.manager.Bot;
import com.gats.manager.Controller;
import com.gats.simulation.*;

import java.util.*;

public class MyBot extends Bot {
    private static final float g = 9.81f * 16;
    private static final float MAX_VELOCITY = 400;

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
        return "Meowdin";
    }

    @Override
    protected void init(GameState gameState) {
    }

    @Override
    protected void executeTurn(GameState gameState, Controller controller) {
        GameCharacter character = controller.getGameCharacter();

        List<Vector2> targets;

        if (character.getHealth() <= (100 - 35)) {
            targets = findHealthBoxes(gameState, character);
        } else {
            targets = findEnemies(gameState, character);
        }

        targets.sort(new Vector2DistanceComparator(character.getPlayerPos()));

        controller.selectWeapon(WeaponType.WATER_PISTOL);

        ShootInfo optimalTarget = null;

        for (Vector2 enemy : targets) {
            ShootInfo info = calculateShootInfo(gameState, character, enemy, 200);

            if (info == null) continue;

            optimalTarget = info;
            break;
        }

        if (optimalTarget != null) {
            controller.move(optimalTarget.moveX);
            controller.aim(optimalTarget.angle, optimalTarget.strength);
            controller.shoot();
        }

        return;
    }

    private ShootInfo calculateShootInfo(GameState gameState, GameCharacter character, Vector2 target, int maxIterations) {

        float optimalVelocity = 0;
        float optimalAngle = 0;
        int optimalOffset = 0;
        int minimumObstructions = 100_000;

        FIND_IDEAL_POSITION:
        for (int offset : getPossiblePositionOffsets(gameState, character)) {
            Vector2 position = new Vector2(character.getPlayerPos()).add(offset, 0);

            Vector2 t = new Vector2(target).sub(position);

            float minimumVelocity = (float) Math.sqrt(g * (t.y + Math.sqrt(t.y * t.y + t.x * t.x)));

            if (minimumVelocity > MAX_VELOCITY) return null;

            if (maxIterations <= 1) maxIterations = 2;

            FIND_IDEAL_VELOCITY:
            for (int i = 0; i < maxIterations; i++) {
                float v = MathUtils.lerp(minimumVelocity, MAX_VELOCITY, (float) i / (maxIterations - 1));

                float[] angles;

                if (i == 0) {
                    angles = new float[] { (float) Math.atan2(v * v, g * t.x) };
                } else {
                    angles = calculateAngles(v, t.x, t.y);
                }

                FIND_IDEAL_ANGLE:
                for (float angle : angles) {
                    int obstructions = numberOfObstructions(gameState, position, target, v, angle, 0.05f);

                    if (obstructions > minimumObstructions) {
                        continue;
                    }

                    optimalVelocity = v;
                    optimalAngle = angle;
                    minimumObstructions = obstructions;
                    optimalOffset = offset;

                    if (obstructions == 0) break FIND_IDEAL_POSITION;
                }
            }
        }

        Vector2 angleV = new Vector2((float) Math.cos(optimalAngle), (float) Math.sin(optimalAngle));
        float strength = optimalVelocity / MAX_VELOCITY;

        return new ShootInfo(minimumObstructions, angleV, strength, optimalOffset);
    }

    private List<Integer> getPossiblePositionOffsets(GameState gameState, GameCharacter character) {
        List<Integer> offsets = new ArrayList<>();
        offsets.add(0);

        for (int rightOffset = 1; rightOffset <= character.getStamina(); rightOffset++) {
            if (getTileBelow(gameState, character.getPlayerPos().add(rightOffset, 0)) == null) {
                break;
            }

            offsets.add(rightOffset);
        }

        for (int leftOffset = 1; leftOffset <= character.getStamina(); leftOffset++) {
            if (getTileBelow(gameState, character.getPlayerPos().add(-leftOffset, 0)) == null) {
                break;
            }

            offsets.add(-leftOffset);
        }

        return offsets;
    }

    private Tile getTileBelow(GameState gameState, Vector2 position) {
        return gameState.getTile(worldToTileCoords(new Vector2(position.x, position.y - 16)));
    }

    private float[] calculateAngles(float v, float x, float y) {
        float delta = (float) Math.sqrt(Math.pow(v, 4) - g * (g * x * x + 2 * y * v * v));

        float high = (float) Math.atan2(v * v + delta, g * x);
        float low = (float) Math.atan2(v * v - delta, g * x);

        return new float[] { high, low };
    }

    private int numberOfObstructions(GameState gameState, Vector2 startPosition, Vector2 target, float v, float angle, float deltaT) {
        Set<Tile> tiles = new HashSet<>();

        double timeToTarget = (2 * v * Math.sin(angle)) / g;

        for (double t = 0; t < timeToTarget; t += deltaT) {
            float x = (float) (startPosition.x + v * t * Math.cos(angle));
            float y = (float) (startPosition.y + v * t * Math.sin(angle) - (0.5) * g * t * t);

            Tile tile = gameState.getTile(worldToTileCoords(new Vector2(x, y)));
            if (tile != null) {
                tiles.add(tile);
            }
        }

        return tiles.size();
    }

    private IntVector2 worldToTileCoords(Vector2 worldCoords) {
        return new IntVector2((int) (worldCoords.x / 16), (int) (worldCoords.y / 16));
    }

    private List<Vector2> findEnemies(GameState gameState, GameCharacter character) {
        List<Vector2> enemies = new ArrayList<>();

        for (int team = 0; team < gameState.getTeamCount(); team++) {
            if (team == character.getTeam()) continue;

            for (int c = 0; c < gameState.getCharactersPerTeam(); c++) {
                GameCharacter enemy = gameState.getCharacterFromTeams(team, c);
                if (!enemy.isAlive()) continue;

                enemies.add(enemy.getPlayerPos());
            }
        }

        return enemies;
    }

    private List<Vector2> findHealthBoxes(GameState gameState, GameCharacter character) {
        List<Vector2> boxes = new ArrayList<>();

        for (int x = 0; x < gameState.getBoardSizeX(); x++) {
            for (int y = 0; y < gameState.getBoardSizeY(); y++) {
                Tile tile = gameState.getTile(x, y);
                if (tile != null && tile.getTileType() == Tile.TileType.HEALTH_BOX) {
                    boxes.add(new Vector2(x * 16, y * 16));
                }
            }
        }

        return boxes;
    }

    private class Vector2DistanceComparator implements Comparator<Vector2> {
        private Vector2 origin;

        public Vector2DistanceComparator(Vector2 origin) {
            this.origin = origin;
        }

        @Override
        public int compare(Vector2 v1, Vector2 v2) {
            return Float.compare(v1.dst(origin), v2.dst(origin));
        }
    }

    private class ShootInfo {
        public final int obstructions;
        public final Vector2 angle;
        public final float strength;
        public final int moveX;

        public ShootInfo(int obstructions, Vector2 angle, float strength, int moveX) {
            this.obstructions = obstructions;
            this.angle = angle;
            this.strength = strength;
            this.moveX = moveX;
        }
    }
}
