package com.projectkorra.projectkorra.firebending;

import static com.projectkorra.projectkorra.firebending.Illumination.isIlluminationTorch;

import java.util.Random;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.util.Vector;

import com.projectkorra.projectkorra.ability.api.ElementalAbility;
import com.projectkorra.projectkorra.ability.api.FireAbility;
import com.projectkorra.projectkorra.airbending.AirSpout;
import com.projectkorra.projectkorra.attribute.Attribute;
import com.projectkorra.projectkorra.configuration.configs.abilities.fire.FireJetConfig;
import com.projectkorra.projectkorra.util.ParticleEffect;

public class FireJet extends FireAbility<FireJetConfig> {

	@Attribute("AvatarStateToggle")
	private boolean avatarStateToggled;
	private long time;
	@Attribute(Attribute.DURATION)
	private long duration;
	@Attribute(Attribute.COOLDOWN)
	private long cooldown;
	@Attribute(Attribute.SPEED)
	private double speed;
	private Random random;
	private Boolean previousGlidingState;
	private Boolean showGliding;

	public FireJet(final FireJetConfig config, final Player player) {
		super(config, player);

		final FireJet oldJet = getAbility(player, FireJet.class);
		if (oldJet != null) {
			oldJet.remove();
			return;
		} else if (this.bPlayer.isOnCooldown(this)) {
			return;
		}

		if (hasAbility(player, AirSpout.class)) {
			final AirSpout abil = getAbility(player, AirSpout.class);
			abil.remove();
		}

		this.avatarStateToggled = config.AvatarState_Toggle;
		this.duration = config.Duration;
		this.speed = config.Speed;
		this.cooldown = config.Cooldown;
		this.showGliding = config.ShowGliding;
		this.random = new Random();

		this.speed = this.getDayFactor(this.speed);
		final Block block = player.getLocation().getBlock();

		if (BlazeArc.isIgnitable(player, block) || ElementalAbility.isAir(block.getType()) || block.getType() == Material.STONE_SLAB || block.getType() == Material.ACACIA_SLAB || block.getType() == Material.BIRCH_SLAB || block.getType() == Material.DARK_OAK_SLAB || block.getType() == Material.JUNGLE_SLAB || block.getType() == Material.OAK_SLAB || block.getType() == Material.SPRUCE_SLAB || isIlluminationTorch(block) || this.bPlayer.isAvatarState()) {
			player.setVelocity(player.getEyeLocation().getDirection().clone().normalize().multiply(this.speed));
			if (!canFireGrief()) {
				if (ElementalAbility.isAir(block.getType())) {
					createTempFire(block.getLocation());
				}

			} else if (ElementalAbility.isAir(block.getType())) {
				block.setType(Material.FIRE);
			}

			this.flightHandler.createInstance(player, this.getName());
			player.setAllowFlight(true);
			this.time = System.currentTimeMillis();

			this.start();

			this.previousGlidingState = player.isGliding();
		}
	}

	@Override
	public void progress() {
		if (this.player.isDead() || !this.player.isOnline()) {
			this.remove();
			return;
		} else if ((isWater(this.player.getLocation().getBlock()) || System.currentTimeMillis() > this.time + this.duration) && (!this.bPlayer.isAvatarState() || !this.avatarStateToggled)) {
			this.remove();
			return;
		} else {
			if (this.random.nextInt(2) == 0) {
				playFirebendingSound(this.player.getLocation());
			}

			ParticleEffect.FLAME.display(this.player.getLocation(), 20, 0.6, 0.6, 0.6);
			ParticleEffect.SMOKE_NORMAL.display(this.player.getLocation(), 10, 0.6, 0.6, 0.6);
			double timefactor;

			if (this.bPlayer.isAvatarState() && this.avatarStateToggled) {
				timefactor = 1;
			} else {
				timefactor = 1 - (System.currentTimeMillis() - this.time) / (2.0 * this.duration);
			}

			final Vector velocity = this.player.getEyeLocation().getDirection().clone().normalize().multiply(this.speed * timefactor);
			this.player.setVelocity(velocity);
			this.player.setFallDistance(0);
			
			if (this.showGliding) {
				player.setGliding(true);
			}
		}
	}

	@Override
	public void remove() {
		super.remove();
		if (this.showGliding) {
			this.player.setGliding(this.previousGlidingState);
		}
		this.flightHandler.removeInstance(this.player, this.getName());
		this.player.setFallDistance(0);
		this.bPlayer.addCooldown(this);
	}

	@Override
	public String getName() {
		return "FireJet";
	}

	@Override
	public Location getLocation() {
		return this.player != null ? this.player.getLocation() : null;
	}

	@Override
	public long getCooldown() {
		return this.cooldown;
	}

	@Override
	public boolean isSneakAbility() {
		return false;
	}

	@Override
	public boolean isHarmlessAbility() {
		return false;
	}

	public boolean isAvatarStateToggled() {
		return this.avatarStateToggled;
	}

	public void setAvatarStateToggled(final boolean avatarStateToggled) {
		this.avatarStateToggled = avatarStateToggled;
	}

	public long getTime() {
		return this.time;
	}

	public void setTime(final long time) {
		this.time = time;
	}

	public long getDuration() {
		return this.duration;
	}

	public void setDuration(final long duration) {
		this.duration = duration;
	}

	public double getSpeed() {
		return this.speed;
	}

	public void setSpeed(final double speed) {
		this.speed = speed;
	}

	public void setCooldown(final long cooldown) {
		this.cooldown = cooldown;
	}
	
	@Override
	public Class<FireJetConfig> getConfigType() {
		return FireJetConfig.class;
	}

}
