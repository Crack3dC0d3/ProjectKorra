package com.projectkorra.projectkorra.airbending.combo;

import java.util.ArrayList;
import java.util.List;

import com.projectkorra.projectkorra.object.HorizontalVelocityTracker;
import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.util.Vector;

import com.projectkorra.projectkorra.GeneralMethods;
import com.projectkorra.projectkorra.ProjectKorra;
import com.projectkorra.projectkorra.ability.api.AirAbility;
import com.projectkorra.projectkorra.ability.api.ComboAbility;
import com.projectkorra.projectkorra.ability.util.Collision;
import com.projectkorra.projectkorra.ability.util.ComboManager.AbilityInformation;
import com.projectkorra.projectkorra.attribute.Attribute;
import com.projectkorra.projectkorra.command.Commands;
import com.projectkorra.projectkorra.configuration.ConfigManager;
import com.projectkorra.projectkorra.configuration.configs.abilities.air.AirSweepConfig;
import com.projectkorra.projectkorra.firebending.combo.FireComboStream;
import com.projectkorra.projectkorra.util.ClickType;
import com.projectkorra.projectkorra.util.DamageHandler;

public class AirSweep extends AirAbility<AirSweepConfig> implements ComboAbility {

	private int progressCounter;
	@Attribute(Attribute.COOLDOWN)
	private long cooldown;
	@Attribute(Attribute.DAMAGE)
	private double damage;
	@Attribute(Attribute.SPEED)
	private double speed;
	@Attribute(Attribute.RANGE)
	private double range;
	@Attribute(Attribute.KNOCKBACK)
	private double knockback;
	private Location origin;
	private Location currentLoc;
	private Location destination;
	private Vector direction;
	private ArrayList<Entity> affectedEntities;
	private ArrayList<BukkitRunnable> tasks;

	public AirSweep(final AirSweepConfig config, final Player player) {
		super(config, player);

		this.affectedEntities = new ArrayList<>();
		this.tasks = new ArrayList<>();

		if (!this.bPlayer.canBendIgnoreBindsCooldowns(this)) {
			return;
		}

		if (this.bPlayer.isOnCooldown(this)) {
			return;
		}

		this.damage = config.Damage;
		this.range = config.Range;
		this.speed = config.Speed;
		this.knockback = config.Knockback;
		this.cooldown = config.Cooldown;

		if (this.bPlayer.isAvatarState()) {
			this.cooldown = 0;
			this.damage = config.AvatarState_Damage;
			this.range = config.AvatarState_Range;
			this.knockback = config.AvatarState_Knockback;
		}

		this.bPlayer.addCooldown(this);
		this.start();
	}

	@Override
	public String getName() {
		return "AirSweep";
	}

	@Override
	public boolean isCollidable() {
		return true;
	}

	@Override
	public void handleCollision(final Collision collision) {
		if (collision.isRemovingFirst()) {
			final ArrayList<BukkitRunnable> newTasks = new ArrayList<>();
			final double collisionDistanceSquared = Math.pow(this.getCollisionRadius() + collision.getAbilitySecond().getCollisionRadius(), 2);
			// Remove all of the streams that are by this specific ourLocation.
			// Don't just do a single stream at a time or this algorithm becomes O(n^2) with Collision's detection algorithm.
			for (final BukkitRunnable task : this.getTasks()) {
				if (task instanceof FireComboStream) {
					final FireComboStream stream = (FireComboStream) task;
					if (stream.getLocation().distanceSquared(collision.getLocationSecond()) > collisionDistanceSquared) {
						newTasks.add(stream);
					} else {
						stream.cancel();
					}
				} else {
					newTasks.add(task);
				}
			}
			this.setTasks(newTasks);
		}
	}

	@Override
	public List<Location> getLocations() {
		final ArrayList<Location> locations = new ArrayList<>();
		for (final BukkitRunnable task : this.getTasks()) {
			if (task instanceof FireComboStream) {
				final FireComboStream stream = (FireComboStream) task;
				locations.add(stream.getLocation());
			}
		}
		return locations;
	}

	@Override
	public void progress() {
		this.progressCounter++;
		if (this.player.isDead() || !this.player.isOnline()) {
			this.remove();
			return;
		} else if (this.currentLoc != null && GeneralMethods.isRegionProtectedFromBuild(this, this.currentLoc)) {
			this.remove();
			return;
		}

		if (this.origin == null) {
			this.direction = this.player.getEyeLocation().getDirection().normalize();
			this.origin = GeneralMethods.getMainHandLocation(player).add(this.direction.clone().multiply(10));
		}
		if (this.progressCounter < 8) {
			return;
		}
		if (this.destination == null) {
			this.destination = GeneralMethods.getMainHandLocation(player).add(GeneralMethods.getMainHandLocation(player).getDirection().normalize().multiply(10));
			final Vector origToDest = GeneralMethods.getDirection(this.origin, this.destination);
			final Location hand = GeneralMethods.getMainHandLocation(player);
			for (double i = 0; i < 30; i++) {
				final Location endLoc = this.origin.clone().add(origToDest.clone().multiply(i / 30));
				if (GeneralMethods.locationEqualsIgnoreDirection(hand, endLoc)) {
					continue;
				}
				final Vector vec = GeneralMethods.getDirection(hand, endLoc);

				final FireComboStream fs = new FireComboStream(this.player, this, vec, hand, this.range, this.speed);
				fs.setDensity(1);
				fs.setSpread(0F);
				fs.setUseNewParticles(true);
				fs.setParticleEffect(getAirbendingParticles());
				fs.setCollides(false);
				fs.runTaskTimer(ProjectKorra.plugin, (long) (i / 2.5), 1L);
				this.tasks.add(fs);
			}
		}
		this.manageAirVectors();
	}

	public void manageAirVectors() {
		for (int i = 0; i < this.tasks.size(); i++) {
			if (((FireComboStream) this.tasks.get(i)).isCancelled()) {
				this.tasks.remove(i);
				i--;
			}
		}
		if (this.tasks.size() == 0) {
			this.remove();
			return;
		}
		for (int i = 0; i < this.tasks.size(); i++) {
			final FireComboStream fstream = (FireComboStream) this.tasks.get(i);
			final Location loc = fstream.getLocation();

			if (GeneralMethods.isRegionProtectedFromBuild(this, loc)) {
				fstream.remove();
				return;
			}

			if (!this.isTransparent(loc.getBlock())) {
				if (!this.isTransparent(loc.clone().add(0, 0.2, 0).getBlock())) {
					fstream.remove();
					return;
				}
			}
			if (i % 3 == 0) {
				for (final Entity entity : GeneralMethods.getEntitiesAroundPoint(loc, 2.5)) {
					if (GeneralMethods.isRegionProtectedFromBuild(this, entity.getLocation())) {
						this.remove();
						return;
					}
					if (!entity.equals(this.player) && !(entity instanceof Player && Commands.invincible.contains(((Player) entity).getName()))) {
						if (this.knockback != 0) {
							final Vector force = fstream.getLocation().getDirection();
							GeneralMethods.setVelocity(entity, force.clone().multiply(this.knockback));
							new HorizontalVelocityTracker(entity, this.player, 200l, this);
							entity.setFallDistance(0);
						}
						if(!this.affectedEntities.contains(entity)) {
							this.affectedEntities.add(entity);
							if (this.damage != 0) {
								if (entity instanceof LivingEntity) {
									DamageHandler.damageEntity(entity, this.damage, this);
								}
							}
						}
					}
				}
			}
		}
	}

	@Override
	public void remove() {
		super.remove();
		for (final BukkitRunnable task : this.tasks) {
			task.cancel();
		}
	}

	@Override
	public boolean isSneakAbility() {
		return true;
	}

	@Override
	public boolean isHarmlessAbility() {
		return false;
	}

	@Override
	public long getCooldown() {
		return this.cooldown;
	}

	@Override
	public Location getLocation() {
		return this.origin;
	}

	@Override
	public Object createNewComboInstance(final Player player) {
		return new AirSweep(ConfigManager.getConfig(AirSweepConfig.class), player);
	}

	@Override
	public ArrayList<AbilityInformation> getCombination() {
		final ArrayList<AbilityInformation> airSweep = new ArrayList<>();
		airSweep.add(new AbilityInformation("AirSwipe", ClickType.LEFT_CLICK));
		airSweep.add(new AbilityInformation("AirSwipe", ClickType.LEFT_CLICK));
		airSweep.add(new AbilityInformation("AirBurst", ClickType.SHIFT_DOWN));
		airSweep.add(new AbilityInformation("AirBurst", ClickType.LEFT_CLICK));
		return airSweep;
	}

	public Location getOrigin() {
		return this.origin;
	}

	public void setOrigin(final Location origin) {
		this.origin = origin;
	}

	public Location getCurrentLoc() {
		return this.currentLoc;
	}

	public void setCurrentLoc(final Location currentLoc) {
		this.currentLoc = currentLoc;
	}

	public Location getDestination() {
		return this.destination;
	}

	public void setDestination(final Location destination) {
		this.destination = destination;
	}

	public Vector getDirection() {
		return this.direction;
	}

	public void setDirection(final Vector direction) {
		this.direction = direction;
	}

	public int getProgressCounter() {
		return this.progressCounter;
	}

	public void setProgressCounter(final int progressCounter) {
		this.progressCounter = progressCounter;
	}

	public double getDamage() {
		return this.damage;
	}

	public void setDamage(final double damage) {
		this.damage = damage;
	}

	public double getSpeed() {
		return this.speed;
	}

	public void setSpeed(final double speed) {
		this.speed = speed;
	}

	public double getRange() {
		return this.range;
	}

	public void setRange(final double range) {
		this.range = range;
	}

	public double getKnockback() {
		return this.knockback;
	}

	public void setKnockback(final double knockback) {
		this.knockback = knockback;
	}

	public ArrayList<Entity> getAffectedEntities() {
		return this.affectedEntities;
	}

	public ArrayList<BukkitRunnable> getTasks() {
		return this.tasks;
	}

	public void setTasks(final ArrayList<BukkitRunnable> tasks) {
		this.tasks = tasks;
	}

	@Override
	public Class<AirSweepConfig> getConfigType() {
		return AirSweepConfig.class;
	}
}
