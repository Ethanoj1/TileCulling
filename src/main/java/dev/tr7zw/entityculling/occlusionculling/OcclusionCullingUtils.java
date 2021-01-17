package dev.tr7zw.entityculling.occlusionculling;

import org.bukkit.ChunkSnapshot;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.util.Vector;

import dev.tr7zw.entityculling.AxisAlignedBB;
import dev.tr7zw.entityculling.CullingPlugin;
import dev.tr7zw.entityculling.occlusionculling.BlockChangeListener.ChunkCoords;

public class OcclusionCullingUtils {

	public static boolean isAABBVisible(Location aabbBlock, AxisAlignedBB aabb, Location playerLoc, boolean entity) {
		try {
			double width = aabb.getWidth();
			double height = aabb.getHeight();
			double depth = aabb.getDepth();
			Location center = entity ? aabbBlock.clone().add(0, height/2, 0) : aabb.getAABBMiddle(aabbBlock).toLocation(aabbBlock.getWorld());
			Location centerXMin = center.clone().add(-width / 2, 0, 0);
			Location centerXMax = center.clone().add(width / 2, 0, 0);
			Location centerYMin = center.clone().add(0, -height / 2, 0);
			Location centerYMax = center.clone().add(0, height / 2, 0);
			Location centerZMin = center.clone().add(0, 0, -depth / 2);
			Location centerZMax = center.clone().add(0, 0, depth / 2);
			boolean centerXMinBlocked = false;
			Vector[] locs = gridRaytrace(playerLoc, centerXMin.subtract(playerLoc).toVector());
			for (Vector v : locs) {
				if (isBlockAtLocationOccluding(v.toLocation(playerLoc.getWorld()))) {
					centerXMinBlocked = true;
					break;
				}
			}
			if(!centerXMinBlocked)return true;
			boolean centerXMaxBlocked = false;
			locs = gridRaytrace(playerLoc, centerXMax.subtract(playerLoc).toVector());
			for (Vector v : locs) {
				if (isBlockAtLocationOccluding(v.toLocation(playerLoc.getWorld()))) {
					centerXMaxBlocked = true;
					break;
				}
			}
			if(!centerXMaxBlocked)return true;
			boolean centerYMinBlocked = false;
			locs = gridRaytrace(playerLoc, centerYMin.subtract(playerLoc).toVector());
			for (Vector v : locs) {
				if (isBlockAtLocationOccluding(v.toLocation(playerLoc.getWorld()))) {
					centerYMinBlocked = true;
					break;
				}
			}
			if(!centerYMinBlocked)return true;
			boolean centerYMaxBlocked = false;
			locs = gridRaytrace(playerLoc, centerYMax.subtract(playerLoc).toVector());
			for (Vector v : locs) {
				if (isBlockAtLocationOccluding(v.toLocation(playerLoc.getWorld()))) {
					centerYMaxBlocked = true;
					break;
				}
			}
			if(!centerYMaxBlocked)return true;
			boolean centerZMinBlocked = false;
			locs = gridRaytrace(playerLoc, centerZMin.subtract(playerLoc).toVector());
			for (Vector v : locs) {
				if (isBlockAtLocationOccluding(v.toLocation(playerLoc.getWorld()))) {
					centerZMinBlocked = true;
					break;
				}
			}
			if(!centerZMinBlocked)return true;
			boolean centerZMaxBlocked = false;
			locs = gridRaytrace(playerLoc, centerZMax.subtract(playerLoc).toVector());
			for (Vector v : locs) {
				if (isBlockAtLocationOccluding(v.toLocation(playerLoc.getWorld()))) {
					centerZMaxBlocked = true;
					break;
				}
			}
			if(!centerZMaxBlocked)return true;
			return false;

		} catch (Exception exception) {
			exception.printStackTrace();
		}
		return true;
	}

	private static boolean isBlockAtLocationOccluding(Location loc) {
		try {
			ChunkSnapshot snapshot = null;
			int chunkX = (int) Math.floor(loc.getBlockX() / 16d);
			int chunkZ = (int) Math.floor(loc.getBlockZ() / 16d);
			ChunkCoords cc = new ChunkCoords(loc.getWorld().getName(), chunkX, chunkZ);
			if (CullingPlugin.instance.blockChangeListener.cachedChunkSnapshots.containsKey(cc)) {
				snapshot = CullingPlugin.instance.blockChangeListener.cachedChunkSnapshots.get(cc);
			}
			if (snapshot == null) {
				return false;
			}
			int relativeX = loc.getBlockX() % 16;
			if (relativeX < 0) {
				relativeX = 16 + relativeX;
			}
			int relativeZ = loc.getBlockZ() % 16;
			if (relativeZ < 0) {
				relativeZ = 16 + relativeZ;
			}
			if (relativeX < 0 || relativeX > 15) {
				return false;
			}
			if (relativeZ < 0 || relativeZ > 15) {
				return false;
			}
			if (loc.getBlockY() < 0 || loc.getBlockY() > 255) {
				return false;
			}
			Material material = snapshot.getBlockType(relativeX, loc.getBlockY(), relativeZ);
			return material.isOccluding() && material != Material.SPAWNER;
		} catch (Exception e) {
			e.printStackTrace();
			return false;
		}
	}

	/**
	 * returns the grid cells that intersect with this vector<br>
	 * <a href=
	 * "http://playtechs.blogspot.de/2007/03/raytracing-on-grid.html">http://playtechs.blogspot.de/2007/03/raytracing-on-grid.html</a>
	 */
	private static Vector[] gridRaytrace(Location start, Vector vector) {

		// coordinates of start and target point
		double x0 = start.getX();
		double y0 = start.getY();
		double z0 = start.getZ();
		double x1 = x0 + vector.getX();
		double y1 = y0 + vector.getY();
		double z1 = z0 + vector.getZ();

		// horizontal and vertical cell amount spanned
		double dx = Math.abs(x1 - x0);
		double dy = Math.abs(y1 - y0);
		double dz = Math.abs(z1 - z0);

		// start cell coordinate
		int x = (int) Math.floor(x0);
		int y = (int) Math.floor(y0);
		int z = (int) Math.floor(z0);

		// distance between horizontal intersection points with cell border as a
		// fraction of the total vector length
		double dt_dx = 1f / dx;
		// distance between vertical intersection points with cell border as a fraction
		// of the total vector length
		double dt_dy = 1f / dy;
		double dt_dz = 1f / dz;

		// fraction of the total vector length
		// determines the progress of the algorithm
		double t = 0;

		// total amount of intersected cells
		int n = 1;

		// 1, 0 or -1
		// determines the direction of the next cell (horizontally / vertically)
		int x_inc, y_inc, z_inc;
		// the distance to the next horizontal / vertical intersection point with a cell
		// border as a fraction of the total vector length
		double t_next_y, t_next_x, t_next_z;

		if (dx == 0f) {
			x_inc = 0;
			t_next_x = dt_dx; // don't increment horizontally because the vector is perfectly vertical
		} else if (x1 > x0) {
			x_inc = 1; // target point is horizontally greater than starting point so increment every
						// step by 1
			n += (int) Math.floor(x1) - x; // increment total amount of intersecting cells
			t_next_x = (float) ((Math.floor(x0) + 1 - x0) * dt_dx); // calculate the next horizontal intersection point based on the position inside
																	// the first cell
		} else {
			x_inc = -1; // target point is horizontally smaller than starting point so reduce every step
						// by 1
			n += x - (int) Math.floor(x1); // increment total amount of intersecting cells
			t_next_x = (float) ((x0 - Math.floor(x0)) * dt_dx); // calculate the next horizontal intersection point based on the position inside
																// the first cell
		}

		if (dy == 0f) {
			y_inc = 0;
			t_next_y = dt_dy; // don't increment vertically because the vector is perfectly horizontal
		} else if (y1 > y0) {
			y_inc = 1; // target point is vertically greater than starting point so increment every
						// step by 1
			n += (int) Math.floor(y1) - y; // increment total amount of intersecting cells
			t_next_y = (float) ((Math.floor(y0) + 1 - y0) * dt_dy); // calculate the next vertical intersection point based on the position inside
																	// the first cell
		} else {
			y_inc = -1; // target point is vertically smaller than starting point so reduce every step
						// by 1
			n += y - (int) Math.floor(y1); // increment total amount of intersecting cells
			t_next_y = (float) ((y0 - Math.floor(y0)) * dt_dy); // calculate the next vertical intersection point based on the position inside
																// the first cell
		}

		if (dz == 0f) {
			z_inc = 0;
			t_next_z = dt_dz; // don't increment vertically because the vector is perfectly horizontal
		} else if (z1 > z0) {
			z_inc = 1; // target point is vertically greater than starting point so increment every
						// step by 1
			n += (int) Math.floor(z1) - z; // increment total amount of intersecting cells
			t_next_z = (float) ((Math.floor(z0) + 1 - z0) * dt_dz); // calculate the next vertical intersection point based on the position inside
																	// the first cell
		} else {
			z_inc = -1; // target point is vertically smaller than starting point so reduce every step
						// by 1
			n += z - (int) Math.floor(z1); // increment total amount of intersecting cells
			t_next_z = (float) ((z0 - Math.floor(z0)) * dt_dz); // calculate the next vertical intersection point based on the position inside
																// the first cell
		}

		// now, the total amount of intersecting cells (n) is correct
		Vector[] intersectingCells = new Vector[n];

		// iterate through all intersecting cells (n times)
		for (; n > 0; n--) {

			// save current cell
			intersectingCells[n - 1] = new Vector(x, y, z);

			if (t_next_y < t_next_x && t_next_y < t_next_z) { // next cell is upwards/downwards because the distance to the next vertical
				// intersection point is smaller than to the next horizontal intersection point
				y += y_inc; // move up/down
				t = t_next_y;
				t_next_y += dt_dy; // update next vertical intersection point
			} else if (t_next_x < t_next_y && t_next_x < t_next_z) { // next cell is right/left
				x += x_inc; // move right/left
				t = t_next_x;
				t_next_x += dt_dx; // update next horizontal intersection point
			} else {
				z += z_inc; // move right/left
				t = t_next_z;
				t_next_z += dt_dz; // update next horizontal intersection point
			}

		}

		return intersectingCells;
	}

}
