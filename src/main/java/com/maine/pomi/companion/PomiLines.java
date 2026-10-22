package com.maine.pomi.companion;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Pomi's canned lines: kills and one-word orders get a random one of these instead of a Grok call,
 * so they cost no tokens. A pool never repeats its last line twice in a row.
 */
public final class PomiLines {
	private static final List<Pool> ALL = new ArrayList<>();

	/** A list of lines that remembers which one came out last. */
	public static final class Pool {
		private final String id;
		private final List<String> lines;
		private int last = -1;

		private Pool(String id, String... lines) {
			this.id = id;
			this.lines = List.of(lines);
			ALL.add(this);
		}

		public String id() {
			return id;
		}

		public List<String> lines() {
			return lines;
		}

		public synchronized String pick() {
			int index = ThreadLocalRandom.current().nextInt(lines.size());
			if (index == last && lines.size() > 1) {
				index = (index + 1) % lines.size();
			}
			last = index;
			return lines.get(index);
		}
	}

	// ---- Kills --------------------------------------------------------------------------------

	public static final Pool KILL_GENERIC = new Pool("kill_generic",
			"¡Croac! Uno menos. Así se hace.",
			"Eso estuvo limpio, casi tan limpio como yo atrapando moscas.",
			"¡Pum! Y ni despeinado quedaste.",
			"¡Bien hecho! Yo lo habría intentado con la lengua y me habría pegado.",
			"Otro más para la lista. Ya pareces cazador profesional.",
			"¡Eso! Ni lo vio venir.",
			"Descanse en paz... o en charco, lo que prefiera.",
			"¡Te vi, te vi! Qué valiente, me temblaban las ancas.",
			"Uno menos en el mundo. El pantano te lo agradece.",
			"¡Eso! Cada vez te sale mejor.",
			"Uy, eso me impresionó un montón.",
			"¡Croac croac! Victoria para el equipo.",
			"Ya ni le dio tiempo de despedirse.",
			"Eso fue rápido. Yo parpadeé y ya no estaba.",
			"Anótalo: jugador uno, mundo cero.",
			"¡Muy bien! Te ganaste una mosca imaginaria.",
			"Bonito golpe. Lo voy a contar en el charco.",
			"Y así, señoras y señores, se hace.",
			"Te aplaudiría pero tengo patitas de rana.",
			"Uno menos. ¿Seguimos o descansamos en un charquito?");

	private static final Pool ZOMBIE = new Pool("kill_zombie",
			"¡Zombi fuera! Olía peor que pantano en verano.",
			"Ese zombi ya estaba muerto, pero ahora sí bien muerto.",
			"Adiós, apestoso. No vuelvas a gruñirme.",
			"¡Croac! Cerebros no, gracias.",
			"Ese zombi caminaba más lento que un caracol con sueño.",
			"Un zombi menos, una noche más tranquila.",
			"Le hiciste un favor, ya se le estaba cayendo la cara.",
			"¿Viste cómo se le cayó la papa? Bueno, a veces sueltan papas.",
			"Zombi derrotado. Ni su abuela lo extraña.",
			"Ese ya no vuelve a tocar puertas de noche.");

	private static final Pool SKELETON = new Pool("kill_skeleton",
			"¡Esqueleto desarmado! Literal, en pedacitos.",
			"Huesitos para el perro, ¿o no?",
			"Tenía arco y todo, pero tú fuiste más rápido.",
			"¡Croac! Ya no más flechitas en la espalda.",
			"Se le acabaron los huesos de la suerte.",
			"Ese esqueleto ya no la arma, se desarmó.",
			"Fíjate si soltó flechas, que andas bien escaso.",
			"Tanto calcio y no le sirvió de nada.",
			"Esqueleto fuera. Ya puedes respirar tantito.",
			"Tip de rana: pelea detrás de un bloque y sus flechas no te alcanzan.");

	private static final Pool CREEPER = new Pool("kill_creeper",
			"¡Creeper eliminado antes del ssss! Qué alivio.",
			"Ese ya no te va a tronar la casa.",
			"¡Croac! Pensé que íbamos a volar los dos.",
			"Creeper fuera. Tu casa te lo agradece.",
			"Ni chance le diste de hacer ssss. Bien ahí.",
			"Junta su pólvora, que para algo sirve.",
			"Ese verde no era de mi familia, eh. Que conste.",
			"Uff, se me pararon los ojos de rana del susto.",
			"Creeper derrotado y sin cráter. Milagro.",
			"Al fin uno que no explota en tu cara.");

	private static final Pool SPIDER = new Pool("kill_spider",
			"¡Araña fuera! Yo prefiero moscas, pero bueno.",
			"Ocho patas y aun así no pudo escapar.",
			"¡Croac! Esa araña me veía con hambre.",
			"Recoge su hilo, que sirve para algo.",
			"Araña derrotada. Ya no más trepar paredes.",
			"Qué asco, pero qué bien le diste.",
			"Ese bicho ya no teje nada.",
			"Una araña menos, una pesadilla menos.",
			"Si eran ojos rojos, ahora son ojos cerrados.",
			"Perfecto, ya me puedo bajar de tu cabeza.");

	private static final Pool ENDERMAN = new Pool("kill_enderman",
			"¡Le ganaste a un enderman! Y lo miraste a los ojos, valiente.",
			"Ese flaco larguirucho ya no se teletransporta.",
			"¡Croac! Junta la perla, que vale oro.",
			"Enderman fuera. Ya puedes ver al frente tranquilo.",
			"Tan alto y tan derrotado.",
			"Ese ya no te roba bloques del jardín.",
			"Uf, esos me dan escalofríos de rana.",
			"Ni teletransportándose se te escapó.",
			"Perla de ender a la bolsa, por favor.",
			"Con ese ya vas para el End, ¿o qué?");

	private static final Pool WITCH = new Pool("kill_witch",
			"¡Bruja fuera! Ya no me va a convertir en príncipe.",
			"Esa bruja hacía pociones peores que tu comida.",
			"¡Croac! Las brujas y las ranas no nos llevamos.",
			"Fíjate si soltó algo bueno, a veces dejan cositas.",
			"Ni con todas sus pociones pudo contigo.",
			"Bruja derrotada. Su escoba es mía.",
			"Ya no más pociones de veneno voladoras.",
			"Esa risita me tenía nerviosa.",
			"Bien hecho, mago. Digo, guerrero.",
			"Una bruja menos en el pantano. ¡Al fin!");

	private static final Pool SLIME = new Pool("kill_slime",
			"¡Slime fuera! Parecía gelatina de limón.",
			"Ese era primo mío del lado verde... no, mentira.",
			"¡Croac! Qué pegajoso estuvo eso.",
			"Junta la bola de slime, sirve para pistones.",
			"Boing, boing, ya no.",
			"Ese brincaba casi tan bonito como yo. Casi.",
			"Uno menos de los que se dividen en más.",
			"Slime derrotado. Gelatina para la cena.",
			"Qué asquito, pero qué bien.",
			"Ese no tenía ni cara, pero igual perdió.");

	private static final Pool DROWNED = new Pool("kill_drowned",
			"¡Ahogado fuera! El agua es mía, ¿oíste?",
			"Ese zombi mojado ya no sale del agua.",
			"¡Croac! Nadie se mete a MI agua.",
			"Fíjate si traía tridente, que sería un premio.",
			"Ahogado derrotado. Ya puedes nadar tranquilo.",
			"Olía a pescado viejo, qué bueno que se fue.",
			"Ese ya se ahogó dos veces.",
			"Bien, el río vuelve a ser de las ranas.",
			"Un ahogado menos, un charco más feliz.",
			"Ni el agua lo salvó.");

	private static final Pool PHANTOM = new Pool("kill_phantom",
			"¡Phantom fuera! Te dije que durmieras.",
			"Ese murciélago feo ya no te va a picar.",
			"¡Croac! Junta la membrana, sirve para reparar élitros.",
			"Phantom derrotado. Ahora sí, a la cama.",
			"Eso te pasa por no dormir, pero bien le diste.",
			"Ya no más ataques desde el cielo.",
			"Volaba feo y cayó feo.",
			"Uno menos. ¿Ya vas a dormir o qué?",
			"Le diste en pleno vuelo, eso tiene mérito.",
			"Phantom fuera. Mis ojos de rana lo seguían.");

	private static final Pool PILLAGER = new Pool("kill_pillager",
			"¡Saqueador fuera! Ya no saquea nada.",
			"Ese señor de ballesta ya no molesta.",
			"¡Croac! Cuidado con su banda, suelen venir en grupo.",
			"Si soltó estandarte, no lo agarres a lo loco.",
			"Saqueador derrotado. Los aldeanos te aplauden.",
			"Esa cara de enojado ya no la vuelvo a ver.",
			"Le ganaste en su propio juego.",
			"Uno menos de esos gruñones.",
			"Bien, la aldea respira tantito.",
			"Ballesta y todo, y aun así perdió.");

	private static final Pool COW = new Pool("kill_cow",
			"Pobre vaquita... bueno, ya hay bistec.",
			"¡Croac! Muuu-y bien, ya hay cuero.",
			"La vaca no te hizo nada, pero se ve rica asada.",
			"Junta el cuero, que sirve para libros.",
			"Bistec en camino. Yo sigo prefiriendo moscas.",
			"Se fue la vaca, llegó la cena.",
			"Muu... perdón, no pude evitarlo.",
			"Hamburguesa en proceso.",
			"Esa vaca daba leche, ¿sabías?",
			"Ok, pero ásala bien, nada de crudo.");

	private static final Pool PIG = new Pool("kill_pig",
			"¡Oink! Ay, pobrecito. Bueno, chuletas.",
			"¡Croac! Tocino para el desayuno.",
			"Ese cerdito era el más bonito del corral.",
			"Chuleta asegurada. No te la comas cruda.",
			"Oink oink, ya no.",
			"Tanto lodo y no le sirvió de escondite.",
			"Ese no tuvo final feliz como en los cuentos.",
			"Cerdito fuera, cena dentro.",
			"Mmm, ya huelo el tocino desde aquí.",
			"Pobre puerquito, pero hay hambre.");

	private static final Pool CHICKEN = new Pool("kill_chicken",
			"¡Pío! Pollito asado en camino.",
			"¡Croac! Plumas para flechas, ¿eh?",
			"Esa gallina ya no pone huevos.",
			"Junta las plumas, las vas a ocupar.",
			"Pollo fuera. Ni corrió tanto.",
			"Pobre gallinita, pero hay hambre.",
			"Pío pío, adiós.",
			"Alitas en camino.",
			"Esa gallina me picoteaba, así que ni modo.",
			"Pollo asado, el clásico.");

	private static final Pool SHEEP = new Pool("kill_sheep",
			"¡Beee! Pobre borreguito.",
			"¡Croac! Lana para tu cama, ¿no?",
			"Esa oveja era bien esponjosa.",
			"Cordero para la cena, qué elegante.",
			"Lana y carne, combo completo.",
			"¿Sabías que con tijeras no se muere? Para la otra.",
			"Beee... ya no.",
			"Esa oveja contaba humanos para dormir.",
			"Borrego fuera. Junta la lana.",
			"Esa lana iba a ser alfombra.");

	/** By the mob's id ("zombie", "cave_spider"). */
	private static final Map<String, Pool> KILLS = Map.ofEntries(
			Map.entry("zombie", ZOMBIE), Map.entry("zombie_villager", ZOMBIE), Map.entry("husk", ZOMBIE),
			Map.entry("skeleton", SKELETON), Map.entry("stray", SKELETON), Map.entry("bogged", SKELETON),
			Map.entry("creeper", CREEPER),
			Map.entry("spider", SPIDER), Map.entry("cave_spider", SPIDER),
			Map.entry("enderman", ENDERMAN),
			Map.entry("witch", WITCH),
			Map.entry("slime", SLIME), Map.entry("magma_cube", SLIME),
			Map.entry("drowned", DROWNED),
			Map.entry("phantom", PHANTOM),
			Map.entry("pillager", PILLAGER), Map.entry("vindicator", PILLAGER),
			Map.entry("cow", COW), Map.entry("mooshroom", COW),
			Map.entry("pig", PIG),
			Map.entry("chicken", CHICKEN),
			Map.entry("sheep", SHEEP));

	/** The line for killing a mob ({@code mobId} like "zombie"): its own pool if it has one, otherwise the generic one. */
	public static String kill(String mobId) {
		return KILLS.getOrDefault(mobId, KILL_GENERIC).pick();
	}

	/** Every pool, in the order they're declared. */
	public static List<Pool> all() {
		return Collections.unmodifiableList(ALL);
	}

	// ---- One-word orders ----------------------------------------------------------------------

	public static final Pool DANCE = new Pool("dance",
			"¡Mira estos pasos! Croac, croac, vuelta.",
			"Esto se llama el brinco del pantano.",
			"Ay, me mareé... pero fue con estilo.",
			"¡Música, maestro! Ah, no hay. Bueno, igual.",
			"Paso de rana, paso de rana, ¡y giro!",
			"Si me vieran en el charco, me aplaudirían.",
			"¡Ritmo de nenúfar!",
			"Este baile lo inventé yo, no lo copies.",
			"Un, dos, croac. Un, dos, croac.",
			"Ups, casi me caigo. Era parte del baile, eh.");

	public static final Pool SING = new Pool("sing",
			"♪ Croac, croac, en mi charquito ♪",
			"♪ Las moscas vuelan y yo las atrapo ♪",
			"♪ Pantano lindo, pantano querido ♪",
			"♪ Do, re, mi, croac, sol ♪",
			"♪ Bajo la lluvia brinco feliz ♪",
			"Ejem... ♪ Cuando sale la luna, croac croac ♪",
			"♪ Nenúfar, nenúfar, llévame a flotar ♪",
			"♪ Soy una rana, una rana bonita ♪",
			"Mi voz es un regalo. ♪ Croooac ♪",
			"♪ Si me das una mosca, te canto otra ♪");

	public static final Pool JUMP = new Pool("jump",
			"¡Boing! ¿Viste qué altura?",
			"Brincar es lo mío, croac.",
			"Creo que vi una mosca allá arriba.",
			"¡Hop, hop, hop!",
			"Esto es calentamiento, nada más.",
			"Si hubiera un charco, caería perfecto.",
			"¡Mira, sin manos! Bueno, nunca uso manos.",
			"Tres brincos, cero esfuerzo.",
			"¡Arriba la rana!",
			"Ni el slime brinca así de bonito.");

	public static final Pool GREET = new Pool("greet",
			"¡Hola, hola! Aquí tu rana favorita.",
			"¡Croac! ¿Qué onda?",
			"Holi. ¿Qué vamos a romper hoy?",
			"¡Hey! Ya te extrañaba, tantito.",
			"Hola, humano. ¿Traes moscas?",
			"¡Qué tal! Lista para la aventura.",
			"Holaaa. Te saludo con mi mejor brinco.",
			"¡Buenas! El charco te manda saludos.",
			"¡Hola! Perdón, me distraje con una mosca.",
			"¡Croac croac! Eso es hola en rana.");

	public static final Pool CROAK = new Pool("croak",
			"¡CROAC!",
			"Croac. Croac. ¿Contento?",
			"¡Croooooac!",
			"Croac croac croac... perdón, me emocioné.",
			"Croac. Eso significa 'tengo hambre'.",
			"¡Croac! Traducción: eres mi humano favorito.",
			"Croac... croac. Es un poema.",
			"¡Croac! Lo dije con sentimiento.",
			"Croac. Así se dice en el pantano.",
			"¡Croac! Mi mejor croac del día.");

	public static final Pool GIVE = new Pool("give",
			"¡Ahí te va! Atrápalo.",
			"Toma, no digas que no comparto.",
			"Ahí está todo. Ni una mosca me quedé.",
			"¡Atrapa! Si se te cae, no es mi culpa.",
			"Tuyo es. Cuídalo mejor que yo.",
			"¡Va de regalo! Bueno, ya era tuyo.",
			"Listo, ya me quedé con las patas vacías.",
			"Ahí te lo aviento, con cariño.",
			"¡Pásalo! Digo, ahí va.",
			"Todo tuyo. Ya me pesaba.");

	public static final Pool GIVE_EMPTY = new Pool("give_empty",
			"No traigo nada, ni una mosca.",
			"¿Dar qué? Tengo las patas vacías.",
			"Nada por aquí, nada por allá. Croac.",
			"Si no me das, no tengo qué darte.",
			"Revisa: no traigo nada.");

	public static final Pool TAKE = new Pool("take",
			"¡Gracias! Lo guardo con cariño.",
			"Mío. Bueno, te lo cuido.",
			"Guardado en mi bolsita de rana.",
			"¡Croac! Me encantan los regalos.",
			"Lo tengo. No lo voy a perder... creo.",
			"Gracias, lo guardo con mis moscas.",
			"¡Atrapado! Ya está conmigo.",
			"¡Yo lo cargo! Para eso son estas patitas.",
			"Ya lo traigo. ¿Algo más, jefe?",
			"Recibido. Mi lengua es muy rápida.");

	public static final Pool TAKE_EMPTY = new Pool("take_empty",
			"¿Tomar qué? Tu mano está vacía.",
			"Mmm, tu mano está vacía... ¿o no veo bien?",
			"Agarra algo primero y luego me lo das.",
			"Mano vacía, regalo vacío. Croac.",
			"Eso es aire. Yo no guardo aire.");

	public static final Pool TAKE_FULL = new Pool("take_full",
			"Ya no me cabe nada, traigo las patas llenas.",
			"Estoy llena. Pídeme que suelte algo primero.",
			"No hay espacio en mi bolsita, croac.",
			"Ni una mosca más me cabe.",
			"Primero pídeme que te dé algo y hago espacio.");

	private PomiLines() {
	}
}
