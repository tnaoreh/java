package com.tommytony.war.mapper;

import java.io.File;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.util.Arrays;
import java.util.List;
import java.util.logging.Level;

import org.apache.commons.lang.StringUtils;
import org.apache.commons.lang.Validate;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Note;
import org.bukkit.Note.Tone;
import org.bukkit.SkullType;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.BlockState;
import org.bukkit.block.CommandBlock;
import org.bukkit.block.CreatureSpawner;
import org.bukkit.block.Jukebox;
import org.bukkit.block.NoteBlock;
import org.bukkit.block.Sign;
import org.bukkit.block.Skull;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.EntityType;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;

import com.tommytony.war.War;
import com.tommytony.war.volume.Volume;
import com.tommytony.war.volume.ZoneVolume;

/**
 * Loads and saves zone blocks to SQLite3 database.
 *
 * @author cmastudios
 * @since 1.8
 */
public class ZoneVolumeMapper {

	public static final int DATABASE_VERSION = 2;

	/**
	 * Loads the given volume
	 *
	 * @param ZoneVolume volume Volume to load
	 * @param String zoneName Zone to load the volume from
	 * @param World world The world the zone is located
	 * @param boolean onlyLoadCorners Should only the corners be loaded
	 * @param start Starting position to load blocks at
	 * @param total Amount of blocks to read
	 * @return integer Changed blocks
	 * @throws SQLException Error communicating with SQLite3 database
	 */
	public static int load(ZoneVolume volume, String zoneName, World world, boolean onlyLoadCorners, int start, int total) throws SQLException {
		int changed = 0;
		File databaseFile = new File(War.war.getDataFolder(), String.format("/dat/warzone-%s/volume-%s.sl3", zoneName, volume.getName()));
		if (!databaseFile.exists()) {
			// Convert warzone to nimitz file format.
			changed = PreNimitzZoneVolumeMapper.load(volume, zoneName, world, onlyLoadCorners);
			ZoneVolumeMapper.save(volume, zoneName);
			War.war.log("Warzone " + zoneName + " converted to nimitz format!", Level.INFO);
			return changed;
		}
		Connection databaseConnection = DriverManager.getConnection("jdbc:sqlite:" + databaseFile.getPath());
		Statement stmt = databaseConnection.createStatement();
		ResultSet versionQuery = stmt.executeQuery("PRAGMA user_version");
		int version = versionQuery.getInt("user_version");
		versionQuery.close();
		if (version > DATABASE_VERSION) {
			stmt.close();
			databaseConnection.close();
			
			// Can't load this too-recent format
			throw new IllegalStateException("Unsupported zone format (was already converted to version: " 
					+ version + ", current format: " + DATABASE_VERSION + ")");
		} else if (version < DATABASE_VERSION) {
			stmt.close();
			
			// We need to migrate to newest schema
			switch (version) {
				// Run some update SQL for each old version
				case 1:
					// Run update from 1 to 2
					updateFromVersionOneToTwo(zoneName, databaseConnection);
					
// How to continue this pattern:
//					// Run update from 2 to 3 (to handle jump from v1 to v3
//					updateFromVersionTwoToTree(zoneName, databaseConnection);
//				case 2:
//					// Run update from 2 to 3
//					updateFromVersionTwoToTree(zoneName, databaseConnection);
			}
			
			// create a new statement to take into account new schema changes
			stmt = databaseConnection.createStatement();
		}
		ResultSet cornerQuery = stmt.executeQuery("SELECT * FROM corners");
		cornerQuery.next();
		final Block corner1 = world.getBlockAt(cornerQuery.getInt("x"), cornerQuery.getInt("y"), cornerQuery.getInt("z"));
		cornerQuery.next();
		final Block corner2 = world.getBlockAt(cornerQuery.getInt("x"), cornerQuery.getInt("y"), cornerQuery.getInt("z"));
		cornerQuery.close();
		volume.setCornerOne(corner1);
		volume.setCornerTwo(corner2);
		if (onlyLoadCorners) {
			stmt.close();
			databaseConnection.close();
			return 0;
		}
		ResultSet query = stmt.executeQuery("SELECT * FROM blocks ORDER BY rowid LIMIT " + start + ", " + total);
		while (query.next()) {
			int x = query.getInt("x"), y = query.getInt("y"), z = query.getInt("z");
			BlockState modify = corner1.getRelative(x, y, z).getState();
			ItemStack data = new ItemStack(Material.valueOf(query.getString("type")), 0, query.getShort("data"));
			modify.setType(data.getType());
			modify.setData(data.getData());
			modify.update(true, false); // No-physics update, preventing the need for deferring blocks
			modify = corner1.getRelative(x, y, z).getState(); // Grab a new instance
			try {
				if (modify instanceof Sign) {
					final String[] lines = query.getString("metadata").split("\n");
					for (int i = 0; i < lines.length; i++) {
						((Sign) modify).setLine(i, lines[i]);
					}
					modify.update(true, false);
				}
				
				// Containers
				if (modify instanceof InventoryHolder && query.getString("metadata") != null) {
					YamlConfiguration config = new YamlConfiguration();
					config.loadFromString(query.getString("metadata"));
					((InventoryHolder) modify).getInventory().clear();
					for (Object obj : config.getList("items")) {
						if (obj instanceof ItemStack) {
							((InventoryHolder) modify).getInventory().addItem((ItemStack) obj);
						}
					}
					modify.update(true, false);
				}
				
				// Notes
				if (modify instanceof NoteBlock) {
					String[] split = query.getString("metadata").split("\n");
					Note note = new Note(Integer.parseInt(split[1]), Tone.valueOf(split[0]), Boolean.parseBoolean(split[2]));
					((NoteBlock) modify).setNote(note);
					modify.update(true, false);
				}
				
				// Records
				if (modify instanceof Jukebox) {
					((Jukebox) modify).setPlaying(Material.valueOf(query.getString("metadata")));
					modify.update(true, false);
				}
				
				// Skulls
				if (modify instanceof Skull && query.getString("metadata") != null) {
					String[] opts = query.getString("metadata").split("\n");
					((Skull) modify).setOwner(opts[0]);
					((Skull) modify).setSkullType(SkullType.valueOf(opts[1]));
					((Skull) modify).setRotation(BlockFace.valueOf(opts[2]));
					modify.update(true, false);
				}
				
				// Command blocks
				if (modify instanceof CommandBlock && query.getString("metadata") != null) {
					final String[] commandArray = query.getString("metadata").split("\n");
					((CommandBlock) modify).setName(commandArray[0]);
					((CommandBlock) modify).setCommand(commandArray[1]);
					modify.update(true, false);
				}
				
				// Creature spawner
				if (modify instanceof CreatureSpawner) {
					((CreatureSpawner) modify).setSpawnedType(EntityType.valueOf(query.getString("metadata")));
					modify.update(true, false);
				}
			} catch (Exception ex) {
				War.war.getLogger().log(Level.WARNING, "Exception loading some tile data. x:" + x + " y:" + y + " z:" + z + " type:" + modify.getType().toString() + " data:" + modify.getData().toString(), ex);
			}
			changed++;
		}
		query.close();
		stmt.close();
		databaseConnection.close();
		return changed;
	}

	/**
	 * Save all war zone blocks to a SQLite3 database file.
	 *
	 * @param volume Volume to save (takes corner data and loads from world).
	 * @param zoneName Name of warzone to save.
	 * @return amount of changed blocks
	 * @throws SQLException
	 */
	public static int save(Volume volume, String zoneName) throws SQLException {
		int changed = 0;
		File warzoneDir = new File(War.war.getDataFolder().getPath() + "/dat/warzone-" + zoneName);
		warzoneDir.mkdirs();
		File databaseFile = new File(War.war.getDataFolder(), String.format("/dat/warzone-%s/volume-%s.sl3", zoneName, volume.getName()));
		Connection databaseConnection = DriverManager.getConnection("jdbc:sqlite:" + databaseFile.getPath());
		Statement stmt = databaseConnection.createStatement();
		stmt.executeUpdate("PRAGMA user_version = " + DATABASE_VERSION);
		stmt.executeUpdate("CREATE TABLE IF NOT EXISTS blocks (x BIGINT, y BIGINT, z BIGINT, type TEXT, data SMALLINT, metadata BLOB)");
		stmt.executeUpdate("CREATE TABLE IF NOT EXISTS corners (pos INTEGER PRIMARY KEY  NOT NULL  UNIQUE, x INTEGER NOT NULL, y INTEGER NOT NULL, z INTEGER NOT NULL)");
		stmt.executeUpdate("DELETE FROM blocks");
		stmt.executeUpdate("DELETE FROM corners");
		stmt.close();
		PreparedStatement cornerStmt = databaseConnection.prepareStatement("INSERT INTO corners SELECT 1 AS pos, ? AS x, ? AS y, ? AS z UNION SELECT 2, ?, ?, ?");
		cornerStmt.setInt(1, volume.getCornerOne().getBlockX());
		cornerStmt.setInt(2, volume.getCornerOne().getBlockY());
		cornerStmt.setInt(3, volume.getCornerOne().getBlockZ());
		cornerStmt.setInt(4, volume.getCornerTwo().getBlockX());
		cornerStmt.setInt(5, volume.getCornerTwo().getBlockY());
		cornerStmt.setInt(6, volume.getCornerTwo().getBlockZ());
		cornerStmt.executeUpdate();
		cornerStmt.close();
		//x, y, z, type, data, sign, container, note, record, skull, command, mobid 
		//x, y, z, type, data, metadata
		PreparedStatement dataStmt = databaseConnection.prepareStatement("INSERT INTO blocks VALUES (?, ?, ?, ?, ?, ?)");
		databaseConnection.setAutoCommit(false);
		final int batchSize = 1000;
		for (int i = 0, x = volume.getMinX(); i < volume.getSizeX(); i++, x++) {
			for (int j = 0, y = volume.getMinY(); j < volume.getSizeY(); j++, y++) {
				for (int k = 0, z = volume.getMinZ(); k < volume.getSizeZ(); k++, z++) {
					// Make sure we are using zone volume-relative coords
					final Block block = volume.getWorld().getBlockAt(x, y, z);
					final Location relLoc = rebase(volume.getCornerOne(), block.getLocation());
					
					dataStmt.setInt(1, relLoc.getBlockX());
					dataStmt.setInt(2, relLoc.getBlockY());
					dataStmt.setInt(3, relLoc.getBlockZ());
					dataStmt.setString(4, block.getType().toString());
					dataStmt.setShort(5, block.getState().getData().toItemStack().getDurability());
					
					// Signs
					if (block.getState() instanceof Sign) {
						final String signText = StringUtils.join(((Sign) block.getState()).getLines(), "\n");
						dataStmt.setString(6, signText);
					} else {
						dataStmt.setNull(6, Types.VARCHAR);
					}
					
					// Containers
					if (block.getState() instanceof InventoryHolder) {
						List<ItemStack> items = Arrays.asList(((InventoryHolder) block.getState()).getInventory().getContents());
						YamlConfiguration config = new YamlConfiguration();
						// Serialize to config, then store config in database
						config.set("items", items);
						dataStmt.setString(6, config.saveToString());
					} else {
						dataStmt.setNull(6, Types.BLOB);
					}
					
					// Notes
					if (block.getState() instanceof NoteBlock) {
						Note note = ((NoteBlock) block.getState()).getNote();
						dataStmt.setString(6, note.getTone().toString() + '\n' + note.getOctave() + '\n' + note.isSharped());
					} else {
						dataStmt.setNull(6, Types.VARCHAR);
					}
					
					// Records
					if (block.getState() instanceof Jukebox) {
						dataStmt.setString(6, ((Jukebox) block.getState()).getPlaying().toString());
					} else {
						dataStmt.setNull(6, Types.VARCHAR);
					}
					
					// Skulls
					if (block.getState() instanceof Skull) {
						dataStmt.setString(6, String.format("%s\n%s\n%s",
								((Skull) block.getState()).getOwner(),
								((Skull) block.getState()).getSkullType().toString(),
								((Skull) block.getState()).getRotation().toString()));
					} else {
						dataStmt.setNull(6, Types.VARCHAR);
					}
					
					// Command blocks
					if (block.getState() instanceof CommandBlock) {
						dataStmt.setString(6, ((CommandBlock) block.getState()).getName()
								+ "\n" + ((CommandBlock) block.getState()).getCommand());
					} else {
						dataStmt.setNull(6, Types.VARCHAR);
					}
					
					// Creature spawners
					if (block.getState() instanceof CreatureSpawner) {
						dataStmt.setString(6, ((CreatureSpawner) block.getState()).getSpawnedType().toString());
					} else {
						dataStmt.setNull(6, Types.VARCHAR);
					}
					
					dataStmt.addBatch();
					
					if (++changed % batchSize == 0) {
						dataStmt.executeBatch();
					}
				}
			}
		}
		dataStmt.executeBatch(); // insert remaining records
		databaseConnection.commit();
		dataStmt.close();
		databaseConnection.close();
		return changed;
	}

	public static Location rebase(final Location base, final Location exact) {
		Validate.isTrue(base.getWorld().equals(exact.getWorld()),
				"Locations must be in the same world");
		return new Location(base.getWorld(),
				exact.getBlockX() - base.getBlockX(),
				exact.getBlockY() - base.getBlockY(),
				exact.getBlockZ() - base.getBlockZ());
	}

	private static void updateFromVersionOneToTwo(String zoneName, Connection connection) throws SQLException {
		War.war.log("Migrating warzone " + zoneName + " from v1 to v2 of schema...", Level.INFO);
		
		// We want to do this in a transaction
		connection.setAutoCommit(false);
		
		Statement stmt = connection.createStatement();
		
		// We want to combine all extra columns into a single metadata BLOB one. To delete some columns we need to drop the table so we use a temp one.
		stmt.executeUpdate("CREATE TEMPORARY TABLE blocks_backup(x BIGINT, y BIGINT, z BIGINT, type TEXT, data SMALLINT, sign TEXT, container BLOB, note INT, record TEXT, skull TEXT, command TEXT, mobid TEXT)");
		stmt.executeUpdate("INSERT INTO blocks_backup SELECT x, y, z, type, data, sign, container, note, record, skull, command, mobid FROM blocks");
		stmt.executeUpdate("DROP TABLE blocks");
		stmt.executeUpdate("CREATE TABLE blocks(x BIGINT, y BIGINT, z BIGINT, type TEXT, data SMALLINT, metadata BLOB)");
		stmt.executeUpdate("INSERT INTO blocks SELECT x, y, z, type, data, coalesce(container, sign, note, record, skull, command, mobid) FROM blocks_backup");
		stmt.executeUpdate("DROP TABLE blocks_backup");	
		stmt.executeUpdate("PRAGMA user_version = 2");
		stmt.close();
		
		// Commit transaction
		connection.commit();
		
		connection.setAutoCommit(true);
		
		War.war.log("Warzone " + zoneName + " converted! Compacting database...", Level.INFO);
		
		// Pack the database otherwise we won't get any space savings.
		// This rebuilds the database completely and takes some time.
		stmt = connection.createStatement();
		stmt.execute("VACUUM");
		stmt.close();
		
		War.war.log("Migration of warzone " + zoneName + " to v2 of schema finished.", Level.INFO);
	}
}
