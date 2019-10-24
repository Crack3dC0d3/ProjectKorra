package com.projectkorra.projectkorra.chiblocking.passive;

import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

import com.projectkorra.projectkorra.ability.api.ChiAbility;
import com.projectkorra.projectkorra.ability.api.PassiveAbility;
import com.projectkorra.projectkorra.attribute.Attribute;
import com.projectkorra.projectkorra.configuration.configs.abilities.chi.ChiAgilityConfig;

public class ChiAgility extends ChiAbility<ChiAgilityConfig> implements PassiveAbility {

	// Configurable variables.
	@Attribute("Jump")
	private int jumpPower;
	@Attribute(Attribute.SPEED)
	private int speedPower;

	public ChiAgility(final ChiAgilityConfig config, final Player player) {
		super(config, player);
		this.setFields();
	}

	public void setFields() {
		this.jumpPower = config.JumpPower - 1;
		this.speedPower = config.SpeedPower - 1;
	}

	@Override
	public void progress() {
		if (!this.player.isSprinting() || !this.bPlayer.canUsePassive(this) || !this.bPlayer.canBendPassive(this)) {
			return;
		}

		// Jump Buff.
		if (!this.player.hasPotionEffect(PotionEffectType.JUMP) || this.player.getPotionEffect(PotionEffectType.JUMP).getAmplifier() < this.jumpPower || (this.player.getPotionEffect(PotionEffectType.JUMP).getAmplifier() == this.jumpPower && this.player.getPotionEffect(PotionEffectType.JUMP).getDuration() == 1)) {
			this.player.addPotionEffect(new PotionEffect(PotionEffectType.JUMP, 10, this.jumpPower, true, false), true);
		}
		// Speed Buff.
		if (!this.player.hasPotionEffect(PotionEffectType.SPEED) || this.player.getPotionEffect(PotionEffectType.SPEED).getAmplifier() < this.speedPower || (this.player.getPotionEffect(PotionEffectType.SPEED).getAmplifier() == this.speedPower && this.player.getPotionEffect(PotionEffectType.SPEED).getDuration() == 1)) {
			this.player.addPotionEffect(new PotionEffect(PotionEffectType.SPEED, 10, this.speedPower, true, false), true);
		}
	}

	@Override
	public boolean isSneakAbility() {
		return false;
	}

	@Override
	public boolean isHarmlessAbility() {
		return true;
	}

	@Override
	public long getCooldown() {
		return 0;
	}

	@Override
	public String getName() {
		return "ChiAgility";
	}

	@Override
	public Location getLocation() {
		return null;
	}

	@Override
	public boolean isInstantiable() {
		return true;
	}

	@Override
	public boolean isProgressable() {
		return true;
	}

	public int getJumpPower() {
		return this.jumpPower;
	}

	public int getSpeedPower() {
		return this.speedPower;
	}
	
	@Override
	public Class<ChiAgilityConfig> getConfigType() {
		return ChiAgilityConfig.class;
	}

}
