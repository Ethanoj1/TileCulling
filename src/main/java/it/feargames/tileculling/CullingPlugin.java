package it.feargames.tileculling;

import com.comphenix.protocol.PacketType;
import com.comphenix.protocol.ProtocolLibrary;
import com.comphenix.protocol.events.*;
import com.comphenix.protocol.wrappers.nbt.NbtBase;
import com.comphenix.protocol.wrappers.nbt.NbtCompound;
import io.netty.buffer.Unpooled;
import it.feargames.tileculling.occlusionculling.BlockChangeListener;
import it.unimi.dsi.fastutil.ints.IntOpenHashSet;
import it.unimi.dsi.fastutil.ints.IntSet;
import net.minecraft.server.v1_16_R3.PacketDataSerializer;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.block.*;
import org.bukkit.entity.Player;
import org.bukkit.inventory.BlockInventoryHolder;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.util.Vector;

import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;

public class CullingPlugin extends JavaPlugin {

	public static final int TASK_INTERVAL = 50;

	public static CullingPlugin instance;

	public BlockChangeListener blockChangeListener;
	public PlayerCache cache;

	private final AtomicBoolean running = new AtomicBoolean(false);
	private Thread thread;

	@Override
	public void onEnable() {
		instance = this;

		blockChangeListener = new BlockChangeListener();
		cache = new PlayerCache();

		getServer().getPluginManager().registerEvents(blockChangeListener, this);
		getServer().getPluginManager().registerEvents(cache, this);

		ProtocolLibrary.getProtocolManager().addPacketListener(new PacketAdapter(this, ListenerPriority.NORMAL,
				Collections.singletonList(PacketType.Play.Server.MAP_CHUNK), ListenerOptions.ASYNC) {
			@Override
			public void onPacketSending(PacketEvent event) {
				try {
					Player player = event.getPlayer();
					PacketContainer packet = event.getPacket();

					if (!player.getName().equals("sgdc3") && !player.getName().equals("tr7zw")) {
						return;
					}

					int chunkX = packet.getIntegers().read(0);
					int chunkZ = packet.getIntegers().read(1);

					List<NbtBase<?>> tileEntities = packet.getListNbtModifier().read(0);
					if (tileEntities.isEmpty()) {
						getLogger().warning(chunkX + "," + chunkZ + ": No tile entities! Skipped.");
						return;
					}

					int bitMask = packet.getIntegers().read(2);
					byte[] chunkData = packet.getByteArrays().read(0);

					IntSet removedBlocks = null;
					for (Iterator<NbtBase<?>> iterator = tileEntities.iterator(); iterator.hasNext(); ) {
						NbtBase<?> tileEntity = iterator.next();
						NbtCompound compound = (NbtCompound) tileEntity;
						String type = compound.getString("id");
						if (!type.equals(Material.CHEST.getKey().toString())) {
							continue;
						}

						iterator.remove();
						if (removedBlocks == null) {
							removedBlocks = new IntOpenHashSet();
						}

						int rawY = compound.getInteger("y");
						byte section = (byte) (rawY & 0xFFFFFFF);
						byte x = (byte) (compound.getInteger("x") & 0xF);
						byte y = (byte) (rawY & 0xF);
						byte z = (byte) (compound.getInteger("z") & 0xF);

						int key = section + (x << 8) + (y << 16) + (z << 24);
						removedBlocks.add(key);

						getLogger().info(section + ": " + x + "," + y + "," + z + " => " + key);
					}
					if (removedBlocks == null) {
						getLogger().warning(chunkX + "," + chunkZ + ": No changed tile entities! Skipped.");
						return;
					}
					getLogger().warning(chunkX + "," + chunkZ + ": Changes detected.");
					packet.getListNbtModifier().write(0, tileEntities);

					PacketDataSerializer reader = new PacketDataSerializer(Unpooled.wrappedBuffer(chunkData));
					PacketDataSerializer writer = new PacketDataSerializer(Unpooled.buffer());
					for (int sectionY = 0; sectionY < 16; sectionY++) {
						if ((bitMask & (1 << sectionY)) == 0) {
							continue;
						}

						short nonAirBlockCount = reader.readShort();
						writer.writeShort(nonAirBlockCount);

						byte bitsPerBlock = reader.readByte();
						if (bitsPerBlock < 4 || bitsPerBlock > 64) {
							throw new RuntimeException("Invalid bits per block! (" + bitsPerBlock + ")");
						}
						boolean direct = bitsPerBlock > 8;
						writer.writeByte(bitsPerBlock);

						int palletteAirIndex;
						if (direct) {
							palletteAirIndex = 0; // Global index
						} else {
							palletteAirIndex = -1;

							int palletLength = reader.readVarInt();
							int[] pallette = new int[palletLength];

							for (int i = 0; i < palletLength; i++) {
								int globalId = reader.readVarInt();
								pallette[i] = globalId;

								if (globalId == 0) {
									palletteAirIndex = i;
								}
							}

							if (palletteAirIndex == -1) {
								// Expand
								int[] newPallette = new int[palletLength + 1];
								System.arraycopy(pallette, 0, newPallette, 0, palletLength);
								pallette = newPallette;
								palletLength++;
								// Add mapping to pallet
								pallette[palletLength - 1] = 0;
								palletteAirIndex = palletLength - 1;
							}

							writer.d(palletLength);
							for (int i = 0; i < palletLength; i++) {
								writer.d(pallette[i]);
							}
						}

						int dataArrayLength = reader.readVarInt();
						writer.b(dataArrayLength);

						long[] dataArray = new long[dataArrayLength];
						for (int i = 0; i < dataArrayLength; i++) {
							dataArray[i] = reader.readLong();
						}

						int individualValueMask = ((1 << bitsPerBlock) - 1);
						byte blocksPerLong = (byte) (64 / bitsPerBlock);

						for (byte y = 0; y < 16; y++) {
							for (byte z = 0; z < 16; z++) {
								for (byte x = 0; x < 16; x++) {
									int key = sectionY + (x << 8) + (y << 16) + (z << 24);
									if (!removedBlocks.contains(key)) {
										continue;
									}

									short blockNumber = (short) ((((y * 16) + z) * 16) + x);
									short longIndex = (short) (blockNumber / blocksPerLong);
									byte startOffset = (byte) (blockNumber % blocksPerLong);

									if (longIndex >= dataArrayLength) {
										getLogger().severe("Tried to access section data at index " + longIndex + " but length was " + dataArrayLength);
										continue;
									}

									int data = (int) (dataArray[longIndex] >> startOffset);
									data &= individualValueMask;

									// TODO: replace data
								}
							}
						}

						for (int i = 0; i < dataArrayLength; i++) {
							writer.writeLong(dataArray[i]);
						}

						// Copy light data
						writer.writeBytes(reader);
					}

					// Replace data
					chunkData = writer.array();
					packet.getByteArrays().write(0, chunkData);

					getLogger().warning(chunkX + "," + chunkZ + ": Processed.");
				} catch (Throwable t) {
					getLogger().log(Level.SEVERE, "WTF", t);
					throw t;
				}
			}
		});

		running.set(true);
		Runnable task = new CullTask(this);
		thread = new Thread(() -> {
			while (running.get()) {
				long start = System.currentTimeMillis();
				task.run();
				long took = System.currentTimeMillis() - start;
				long sleep = Math.max(0, TASK_INTERVAL - took);
				if (sleep > 0) {
					try {
						Thread.sleep(sleep);
					} catch (InterruptedException e) {
						Thread.currentThread().interrupt();
					}
				}
			}
		});
		thread.start();
	}

	@Override
	public void onDisable() {
		ProtocolLibrary.getProtocolManager().removePacketListeners(this);
		running.set(false);
		if (thread != null) {
			thread.interrupt();
		}
	}

	public static void runTask(Runnable task) {
		if (!CullingPlugin.instance.isEnabled()) {
			return;
		}
		Bukkit.getScheduler().runTask(CullingPlugin.instance, task);
	}

	public static boolean shouldHide(BlockState state) {
		return state instanceof BlockInventoryHolder
				|| state instanceof EnderChest
				|| state instanceof CreatureSpawner
				|| state instanceof EnchantingTable
				|| state instanceof Banner
				|| state instanceof Skull
				//|| state instanceof Sign
				;
	}

	public static boolean isOccluding(Material material) {
		switch (material) {
			case BARRIER:
			case SPAWNER:
				return false;
		}
		// TODO: are we sure we want to use this?
		return material.isOccluding();
	}

	public static void particle(Player player, Particle particle, Vector... vectors) {
		if (!player.getName().equals("sgdc3")) {
			return;
		}
		if (CullTask.particleTick <= CullTask.PARTICLE_INTERVAL) {
			return;
		}
		for (Vector vector : vectors) {
			player.spawnParticle(particle, vector.getX(), vector.getY(), vector.getZ(), 1, 0, 0, 0, 0);
		}
	}

	public static void blockParticles(Player player, Particle particle, Vector vector) {
		if (!player.getName().equals("sgdc3")) {
			return;
		}
		if (CullTask.particleTick <= CullTask.PARTICLE_INTERVAL) {
			return;
		}
		int x = vector.getBlockX();
		int y = vector.getBlockY();
		int z = vector.getBlockZ();
		player.spawnParticle(particle, x, y, z, 1);
		player.spawnParticle(particle, x + 1, y, z, 1);
		player.spawnParticle(particle, x, y, z + 1, 1);
		player.spawnParticle(particle, x + 1, y, z + 1, 1);
		player.spawnParticle(particle, x, y + 1, z, 1);
		player.spawnParticle(particle, x + 1, y + 1, z, 1);
		player.spawnParticle(particle, x, y + 1, z + 1, 1);
		player.spawnParticle(particle, x + 1, y + 1, z + 1, 1);
	}
}
