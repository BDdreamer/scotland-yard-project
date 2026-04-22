package uk.ac.bris.cs.scotlandyard.ai;

import uk.ac.bris.cs.scotlandyard.model.Colour;
import uk.ac.bris.cs.scotlandyard.model.Player;

/**
 * Marker {@link PlayerFactory} for the setup-screen "Human" entry. Never used to play moves;
 * {@link LocalGame} skips this when building the {@link AIPool}.
 */
public final class HumanPlaceholderFactory implements PlayerFactory {

	public HumanPlaceholderFactory() {}

	@Override
	public Player createPlayer(Colour colour) {
		throw new UnsupportedOperationException("HumanPlaceholderFactory is UI-only");
	}
}
