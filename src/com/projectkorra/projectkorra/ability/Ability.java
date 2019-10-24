package com.projectkorra.projectkorra.ability;

import com.projectkorra.projectkorra.BendingPlayer;
import com.projectkorra.projectkorra.Manager;
import com.projectkorra.projectkorra.ProjectKorra;
import com.projectkorra.projectkorra.ability.api.AddonAbility;
import com.projectkorra.projectkorra.ability.api.ComboAbility;
import com.projectkorra.projectkorra.ability.api.MultiAbility;
import com.projectkorra.projectkorra.ability.api.PassiveAbility;
import com.projectkorra.projectkorra.ability.loader.AddonAbilityLoader;
import com.projectkorra.projectkorra.ability.util.*;
import com.projectkorra.projectkorra.ability.util.MultiAbilityManager.MultiAbilityInfo;
import com.projectkorra.projectkorra.attribute.Attribute;
import com.projectkorra.projectkorra.attribute.AttributeModifier;
import com.projectkorra.projectkorra.attribute.AttributePriority;
import com.projectkorra.projectkorra.configuration.ConfigManager;
import com.projectkorra.projectkorra.configuration.configs.abilities.AbilityConfig;
import com.projectkorra.projectkorra.element.Element;
import com.projectkorra.projectkorra.event.AbilityEndEvent;
import com.projectkorra.projectkorra.event.AbilityStartEvent;
import com.projectkorra.projectkorra.module.ModuleManager;
import com.projectkorra.projectkorra.util.FlightHandler;
import com.projectkorra.projectkorra.util.TimeUtil;
import org.apache.commons.lang3.Validate;
import org.apache.commons.lang3.builder.ToStringBuilder;
import org.apache.commons.lang3.builder.ToStringStyle;
import org.apache.commons.lang3.tuple.Pair;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.lang.reflect.Field;
import java.lang.reflect.ParameterizedType;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.jar.JarFile;

/**
 * Ability provides default implementation of an Ability, including methods
 * to control the life cycle of a specific instance. Ability also provides a
 * system to load CoreAbilities within a {@link JavaPlugin}, or located in an
 * external {@link JarFile}.
 * <p>
 * For {@link CollisionManager} and {@link Collision}, a Ability may need to
 * override {@link #isCollidable()}, {@link #getCollisionRadius()},
 * {@link #handleCollision(Collision)}, and {@link #getLocations()}.
 *
 * @see #start()
 * @see #progress()
 * @see #remove()
 * @see #registerAddonAbilities(String)
 * @see #registerPluginAbilities(JavaPlugin, String)
 */
public abstract class Ability<Config extends AbilityConfig> {

	private static final double DEFAULT_COLLISION_RADIUS = 0.3;
	private static final List<String> ADDON_PLUGINS = new ArrayList<>();
	private static final Map<Class<? extends Ability>, Map<String, Field>> ATTRIBUTE_FIELDS = new HashMap<>();

	private static int idCounter;

	protected final AbilityManager manager = ModuleManager.getModule(AbilityManager.class);
	protected final Config config = ConfigManager.getConfig(((Class<Config>) ((ParameterizedType) getClass().getGenericSuperclass()).getActualTypeArguments()[0]));

	protected Player player;
	protected BendingPlayer bPlayer;
	protected FlightHandler flightHandler;

	private final Map<String, Map<AttributePriority, Set<Pair<Number, AttributeModifier>>>> attributeModifiers = new HashMap<>();
	private final Map<String, Object> attributeValues = new HashMap<>();
	private boolean started;
	private boolean removed;
	private boolean hidden;
	private int id;
	private long startTime;
	private long startTick;
	private boolean attributesModified;

	static {
		idCounter = Integer.MIN_VALUE;
	}

	/**
	 * The default constructor is needed to create a fake instance of each
	 * Ability via reflection in {@link #registerAbilities()}. More
	 * specifically, {@link #registerPluginAbilities} calls
	 * getDeclaredConstructor which is only usable with a public default
	 * constructor. Reflection lets us create a list of all of the plugin's
	 * abilities when the plugin first loads.
	 *
	 * @see #ABILITIES_BY_NAME
	 * @see #getAbility(String)
	 */
	public Ability() {
		for (final Field field : this.getClass().getDeclaredFields()) {
			if (field.isAnnotationPresent(Attribute.class)) {
				final Attribute attribute = field.getAnnotation(Attribute.class);
				if (!ATTRIBUTE_FIELDS.containsKey(this.getClass())) {
					ATTRIBUTE_FIELDS.put(this.getClass(), new HashMap<>());
				}
				ATTRIBUTE_FIELDS.get(this.getClass()).put(attribute.value(), field);
			}
		}
	}

	/**
	 * Creates a new Ability instance but does not start it.
	 *
	 * @param player the non-null player that created this instance
	 * @see #start()
	 */
	public Ability(final Config config, final Player player) {
		if (player == null || !this.isEnabled()) {
			return;
		}
		
		this.player = player;
		this.bPlayer = BendingPlayer.getBendingPlayer(player);
		this.flightHandler = Manager.getManager(FlightHandler.class);
		this.startTime = System.currentTimeMillis();
		this.started = false;
		this.id = Ability.idCounter;
		this.startTick = this.getCurrentTick();

		if (idCounter == Integer.MAX_VALUE) {
			idCounter = Integer.MIN_VALUE;
		} else {
			idCounter++;
		}
	}

	/**
	 * Causes the ability to begin updating every tick by calling
	 * {@link #progress()} until {@link #remove()} is called. This method cannot
	 * be overridden, and any code that needs to be performed before start
	 * should be handled in the constructor.
	 *
	 * @see #getStartTime()
	 * @see #isStarted()
	 * @see #isRemoved()
	 */
	public final void start() {
		if (this.player == null || !this.isEnabled()) {
			return;
		}
		final AbilityStartEvent event = new AbilityStartEvent(this);
		Bukkit.getServer().getPluginManager().callEvent(event);
		if (event.isCancelled()) {
			this.remove();
			return;
		}

		this.started = true;
		this.startTime = System.currentTimeMillis();
		final Class<? extends Ability> clazz = this.getClass();
		final UUID uuid = this.player.getUniqueId();

		this.manager.startAbility(this);
	}

	/**
	 * Causes this Ability instance to be removed, and {@link #progress}
	 * will no longer be called every tick. If this method is overridden then
	 * the new method must call <b>super.remove()</b>.
	 *
	 * {@inheritDoc}
	 *
	 * @see #isRemoved()
	 */
	public void remove() {
		if (this.player == null) {
			return;
		}

		Bukkit.getServer().getPluginManager().callEvent(new AbilityEndEvent(this));
		this.removed = true;

		this.manager.removeAbility(this);
	}

	protected void tryModifyAttributes() {
		if (!this.attributesModified) {
			modifyAttributes();
			this.attributesModified = true;
		}
	}

	/**
	 * Ability keeps track of plugins that have registered abilities to use
	 * for bending reload purposes <br>
	 * <b>This isn't a simple list, external use isn't recommended</b>
	 *
	 * @return a list of entrys with the plugin name and path abilities can be
	 *         found at
	 */
	public static List<String> getAddonPlugins() {
		return ADDON_PLUGINS;
	}

	/**
	 * Scans a JavaPlugin and registers Ability class files.
	 *
	 * @param plugin a JavaPlugin containing Ability class files
	 * @param packageBase a prefix of the package name, used to increase
	 *            performance
	 * @see #getAbilities()
	 * @see #getAbility(String)
	 */
	public static void registerPluginAbilities(final JavaPlugin plugin, final String packageBase) {
		final com.projectkorra.projectkorra.ability.loader.AbilityLoader<Ability> abilityLoader = new com.projectkorra.projectkorra.ability.loader.AbilityLoader<Ability>(plugin, packageBase);
		final List<Ability> loadedAbilities = abilityLoader.load(Ability.class, Ability.class);
		final String entry = plugin.getName() + "::" + packageBase;
		if (!ADDON_PLUGINS.contains(entry)) {
			ADDON_PLUGINS.add(entry);
		}

		for (final Ability coreAbil : loadedAbilities) {
			if (!coreAbil.isEnabled()) {
				plugin.getLogger().info(coreAbil.getName() + " is disabled");
				continue;
			}

			final String name = coreAbil.getName();

			if (name == null) {
				plugin.getLogger().warning("Ability " + coreAbil.getClass().getName() + " has no name?");
				continue;
			}

			try {
				ABILITIES_BY_NAME.put(name.toLowerCase(), coreAbil);
				ABILITIES_BY_CLASS.put(coreAbil.getClass(), coreAbil);

				if (coreAbil instanceof ComboAbility) {
					final ComboAbility combo = (ComboAbility) coreAbil;
					if (combo.getCombination() != null) {
						ComboManager.getComboAbilities().put(name, new ComboManager.ComboAbilityInfo(name, combo.getCombination(), combo));
						ComboManager.getDescriptions().put(name, coreAbil.getDescription());
						ComboManager.getInstructions().put(name, coreAbil.getInstructions());
					}
				}

				if (coreAbil instanceof MultiAbility) {
					final MultiAbility multiAbil = (MultiAbility) coreAbil;
					MultiAbilityManager.multiAbilityList.add(new MultiAbilityInfo(name, multiAbil.getMultiAbilities()));
				}

				if (coreAbil instanceof PassiveAbility) {
					PassiveAbility passive = (PassiveAbility) coreAbil;
					coreAbil.setHiddenAbility(true);
					PassiveManager.getPassives().put(name, coreAbil);
					if (!PassiveManager.getPassiveClasses().containsKey(passive)) {
						PassiveManager.getPassiveClasses().put(passive, coreAbil.getClass());
					}
				}
			} catch (Exception | Error e) {
				plugin.getLogger().warning("The ability " + coreAbil.getName() + " was not able to load, if this message shows again please remove it!");
				e.printStackTrace();
				ABILITIES_BY_NAME.remove(name.toLowerCase());
				ABILITIES_BY_CLASS.remove(coreAbil.getClass());
			}
		}
	}

	/**
	 * Scans all of the Jar files inside of /ProjectKorra/folder and registers
	 * all of the Ability class files that were found.
	 *
	 * @param folder the name of the folder to scan
	 * @see #getAbilities()
	 * @see #getAbility(String)
	 */
	public static void registerAddonAbilities(final String folder) {
		final ProjectKorra plugin = ProjectKorra.plugin;
		final File path = new File(plugin.getDataFolder().toString() + folder);
		if (!path.exists()) {
			path.mkdir();
			return;
		}

		final com.projectkorra.projectkorra.ability.loader.AddonAbilityLoader<Ability> abilityLoader = new AddonAbilityLoader<Ability>(plugin, path);
		final List<Ability> loadedAbilities = abilityLoader.load(Ability.class, Ability.class);

		for (final Ability coreAbil : loadedAbilities) {
			if (!(coreAbil instanceof AddonAbility)) {
				plugin.getLogger().warning(coreAbil.getName() + " is an addon ability and must implement the AddonAbility interface");
				continue;
			} else if (!coreAbil.isEnabled()) {
				plugin.getLogger().info(coreAbil.getName() + " is disabled");
				continue;
			}

			final AddonAbility addon = (AddonAbility) coreAbil;
			final String name = coreAbil.getName();

			try {
				addon.load();
				ABILITIES_BY_NAME.put(name.toLowerCase(), coreAbil);
				ABILITIES_BY_CLASS.put(coreAbil.getClass(), coreAbil);

				if (coreAbil instanceof ComboAbility) {
					final ComboAbility combo = (ComboAbility) coreAbil;
					if (combo.getCombination() != null) {
						ComboManager.getComboAbilities().put(name, new ComboManager.ComboAbilityInfo(name, combo.getCombination(), combo));
						ComboManager.getDescriptions().put(name, coreAbil.getDescription());
						ComboManager.getInstructions().put(name, coreAbil.getInstructions());
						ComboManager.getAuthors().put(name, addon.getAuthor());
					}
				}

				if (coreAbil instanceof MultiAbility) {
					final MultiAbility multiAbil = (MultiAbility) coreAbil;
					MultiAbilityManager.multiAbilityList.add(new MultiAbilityInfo(name, multiAbil.getMultiAbilities()));
				}

				if (coreAbil instanceof PassiveAbility) {
					PassiveAbility passive = (PassiveAbility) coreAbil;
					coreAbil.setHiddenAbility(true);
					PassiveManager.getPassives().put(name, coreAbil);
					if (!PassiveManager.getPassiveClasses().containsKey(passive)) {
						PassiveManager.getPassiveClasses().put(passive, coreAbil.getClass());
					}
				}
			} catch (Exception | Error e) {
				plugin.getLogger().warning("The ability " + coreAbil.getName() + " was not able to load, if this message shows again please remove it!");
				e.printStackTrace();
				addon.stop();
				ABILITIES_BY_NAME.remove(name.toLowerCase());
				ABILITIES_BY_CLASS.remove(coreAbil.getClass());
			}
		}
	}

	public long getStartTime() {
		return this.startTime;
	}

	public long getStartTick() {
		return this.startTick;
	}

	public long getCurrentTick() {
		return this.player.getWorld().getFullTime();
	}

	public boolean isStarted() {
		return this.started;
	}

	public boolean isRemoved() {
		return this.removed;
	}

	public BendingPlayer getBendingPlayer() {
		return this.bPlayer;
	}

	public int getId() {
		return this.id;
	}
	
	public abstract Class<Config> getConfigType();

	public boolean isHiddenAbility() {
		return this.hidden;
	}

	public void setHiddenAbility(final boolean hidden) {
		this.hidden = hidden;
	}

	public String getInstructions() {
		return config.Instructions;
	}

	public String getDescription() {
		return config.Description;
	}

	public String getMovePreview(final Player player) {
		final BendingPlayer bPlayer = BendingPlayer.getBendingPlayer(player);
		String displayedMessage = "";
		if (bPlayer.isOnCooldown(this)) {
			final long cooldown = bPlayer.getCooldown(this.getName()) - System.currentTimeMillis();
			displayedMessage = this.getElement().getColor() + "" + ChatColor.STRIKETHROUGH + this.getName() + "" + this.getElement().getColor() + " - " + TimeUtil.formatTime(cooldown);
		} else {
			if (bPlayer.getStance() != null && bPlayer.getStance().getName().equals(this.getName())) {
				displayedMessage = this.getElement().getColor() + "" + ChatColor.UNDERLINE + this.getName();
			} else {
				displayedMessage = this.getElement().getColor() + this.getName();
			}
		}
		return displayedMessage;
	}

	public Player getPlayer() {
		return this.player;
	}

	/**
	 * Changes the player that owns this ability instance. Used for redirection
	 * and other abilities that change the player object.
	 *
	 * @param target The player who now controls the ability
	 */
	public void setPlayer(final Player target) {
		if (target == this.player) {
			return;
		}

		final Class<? extends Ability> clazz = this.getClass();

		// The mapping from player UUID to a map of the player's instances.
		Map<UUID, Map<Integer, Ability>> classMap = INSTANCES_BY_PLAYER.get(clazz);

		if (classMap != null) {
			// The map of AbilityId to Ability for the current player.
			final Map<Integer, Ability> playerMap = classMap.get(this.player.getUniqueId());

			if (playerMap != null) {
				// Remove the ability from the current player's map.
				playerMap.remove(this.id);

				if (playerMap.isEmpty()) {
					// Remove the player's empty ability map from global instances map.
					classMap.remove(this.player.getUniqueId());
				}
			}

			if (classMap.isEmpty()) {
				INSTANCES_BY_PLAYER.remove(this.getClass());
			}
		}

		// Add a new map for the current ability if it doesn't exist in the global map.
		if (!INSTANCES_BY_PLAYER.containsKey(clazz)) {
			INSTANCES_BY_PLAYER.put(clazz, new ConcurrentHashMap<>());
		}

		classMap = INSTANCES_BY_PLAYER.get(clazz);

		// Create an AbilityId to Ability map for the target player if it doesn't exist.
		if (!classMap.containsKey(target.getUniqueId())) {
			classMap.put(target.getUniqueId(), new ConcurrentHashMap<>());
		}

		// Add the current instance to the target player's ability map.
		classMap.get(target.getUniqueId()).put(this.getId(), this);

		this.player = target;

		final BendingPlayer newBendingPlayer = BendingPlayer.getBendingPlayer(target);
		if (newBendingPlayer != null) {
			this.bPlayer = newBendingPlayer;
		}
	}

	/**
	 * Used by the CollisionManager to check if two instances can collide with
	 * each other. For example, an EarthBlast is not collidable right when the
	 * person selects a source block, but it is collidable once the block begins
	 * traveling.
	 *
	 * @return true if the instance is currently collidable
	 * @see CollisionManager
	 */
	public boolean isCollidable() {
		return true;
	}

	/**
	 * The radius for collision of the ability instance. Some circular abilities
	 * are better represented with 1 single Location with a small or large
	 * radius, such as AirShield, FireShield, EarthSmash, WaterManipulation,
	 * EarthBlast, etc. Some abilities consist of multiple Locations with small
	 * radiuses, such as AirSpout, WaterSpout, Torrent, RaiseEarth, AirSwipe,
	 * FireKick, etc.
	 *
	 * @return the radius for a location returned by {@link #getLocations()}
	 * @see CollisionManager
	 */
	public double getCollisionRadius() {
		return DEFAULT_COLLISION_RADIUS;
	}

	/**
	 * Called when this ability instance collides with another. Some abilities
	 * may want advanced behavior on a Collision; e.g. FireCombos only remove
	 * the stream that was hit rather than the entire ability.
	 * <p>
	 * collision.getAbilitySecond() - the ability that we are colliding with
	 * collision.isRemovingFirst() - if this ability should be removed
	 * <p>
	 * This ability should only worry about itself because handleCollision will
	 * be called for the other ability instance as well.
	 *
	 * @param collision with data about the other ability instance
	 * @see CollisionManager
	 */
	public void handleCollision(final Collision collision) {
		if (collision.isRemovingFirst()) {
			this.remove();
		}
	}

	/**
	 * A List of Locations used to represent the ability. Some abilities might
	 * just be 1 Location with a radius, while some might be multiple Locations
	 * with small radiuses.
	 *
	 * @return a List of the ability's locations
	 * @see CollisionManager
	 */
	public List<Location> getLocations() {
		final ArrayList<Location> locations = new ArrayList<>();
		locations.add(this.getLocation());
		return locations;
	}

	public Ability addAttributeModifier(final String attribute, final Number value, final AttributeModifier modification) {
		return this.addAttributeModifier(attribute, value, modification, AttributePriority.MEDIUM);
	}

	public Ability addAttributeModifier(final String attribute, final Number value, final AttributeModifier modificationType, final AttributePriority priority) {
		Validate.notNull(attribute, "attribute cannot be null");
		Validate.notNull(value, "value cannot be null");
		Validate.notNull(modificationType, "modifierMethod cannot be null");
		Validate.notNull(priority, "priority cannot be null");
		Validate.isTrue(ATTRIBUTE_FIELDS.containsKey(this.getClass()) && ATTRIBUTE_FIELDS.get(this.getClass()).containsKey(attribute), "Attribute " + attribute + " is not a defined Attribute for " + this.getName());
		if (!this.attributeModifiers.containsKey(attribute)) {
			this.attributeModifiers.put(attribute, new HashMap<>());
		}
		if (!this.attributeModifiers.get(attribute).containsKey(priority)) {
			this.attributeModifiers.get(attribute).put(priority, new HashSet<>());
		}
		this.attributeModifiers.get(attribute).get(priority).add(Pair.of(value, modificationType));
		return this;
	}

	public Ability setAttribute(final String attribute, final Object value) {
		Validate.notNull(attribute, "attribute cannot be null");
		Validate.notNull(value, "value cannot be null");
		Validate.isTrue(ATTRIBUTE_FIELDS.containsKey(this.getClass()) && ATTRIBUTE_FIELDS.get(this.getClass()).containsKey(attribute), "Attribute " + attribute + " is not a defined Attribute for " + this.getName());
		this.attributeValues.put(attribute, value);
		return this;
	}

	private void modifyAttributes() {
		for (final String attribute : this.attributeModifiers.keySet()) {
			final Field field = ATTRIBUTE_FIELDS.get(this.getClass()).get(attribute);
			final boolean accessibility = field.isAccessible();
			field.setAccessible(true);
			try {
				for (final AttributePriority priority : AttributePriority.values()) {
					if (this.attributeModifiers.get(attribute).containsKey(priority)) {
						for (final Pair<Number, AttributeModifier> pair : this.attributeModifiers.get(attribute).get(priority)) {
							final Object get = field.get(this);
							Validate.isTrue(get instanceof Number, "The field " + field.getName() + " cannot algebraically be modified.");
							final Number oldValue = (Number) field.get(this);
							final Number newValue = pair.getRight().performModification(oldValue, pair.getLeft());
							field.set(this, newValue);
						}
					}
				}
			} catch (IllegalArgumentException | IllegalAccessException e) {
				e.printStackTrace();
			} finally {
				field.setAccessible(accessibility);
			}
		}
		this.attributeValues.forEach((attribute, value) -> {
			final Field field = ATTRIBUTE_FIELDS.get(this.getClass()).get(attribute);
			final boolean accessibility = field.isAccessible();
			field.setAccessible(true);
			try {
				field.set(this, value);
			} catch (IllegalArgumentException | IllegalAccessException e) {
				e.printStackTrace();
			} finally {
				field.setAccessible(accessibility);
			}
		});
	}

	/**
	 * Returns a String used to debug potential Ability memory that can be
	 * caused by a developer forgetting to call {@link #remove()}
	 */
	public static String getDebugString() {
		final StringBuilder sb = new StringBuilder();
		int playerCounter = 0;
		final HashMap<String, Integer> classCounter = new HashMap<>();

		for (final Map<UUID, Map<Integer, Ability>> map1 : INSTANCES_BY_PLAYER.values()) {
			playerCounter++;
			for (final Map<Integer, Ability> map2 : map1.values()) {
				for (final Ability coreAbil : map2.values()) {
					final String simpleName = coreAbil.getClass().getSimpleName();

					if (classCounter.containsKey(simpleName)) {
						classCounter.put(simpleName, classCounter.get(simpleName) + 1);
					} else {
						classCounter.put(simpleName, 1);
					}
				}
			}
		}

		for (final Set<Ability> set : INSTANCES_BY_CLASS.values()) {
			for (final Ability coreAbil : set) {
				final String simpleName = coreAbil.getClass().getSimpleName();
				if (classCounter.containsKey(simpleName)) {
					classCounter.put(simpleName, classCounter.get(simpleName) + 1);
				} else {
					classCounter.put(simpleName, 1);
				}
			}
		}

		sb.append("Class->UUID's in memory: " + playerCounter + "\n");
		sb.append("Abilities in memory:\n");
		for (final String className : classCounter.keySet()) {
			sb.append(className + ": " + classCounter.get(className) + "\n");
		}
		return sb.toString();
	}

	public abstract void progress();

	public abstract boolean isSneakAbility();

	public abstract boolean isHarmlessAbility();

	public abstract boolean isIgniteAbility();

	public abstract boolean isExplosiveAbility();

	public abstract long getCooldown();

	public abstract String getName();

	public abstract Element getElement();

	public abstract Location getLocation();

	public static double getDefaultCollisionRadius() {
		return DEFAULT_COLLISION_RADIUS;
	}

	@Override
	public String toString() {
		return ToStringBuilder.reflectionToString(this, ToStringStyle.MULTI_LINE_STYLE);
	}

}
