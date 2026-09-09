package org.mortbay.sailing.jinx.config;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mortbay.sailing.jinx.config.JinxConfig.Algorithm;
import org.mortbay.sailing.jinx.config.JinxConfig.PenaltyScaling;
import org.mortbay.sailing.jinx.config.JinxConfig.Variant;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.closeTo;
import static org.hamcrest.Matchers.equalTo;

/**
 * The four handicap variants are two independent knobs, not four algorithms:
 * how a penalty scales with the length of the race, and how the pool comes back.
 *
 * <pre>
 *   Variant | penaltyScaling | givebackGamma
 *      A    | fixed          | 0.0
 *      B    | fixed          | 1.0
 *      C    | perHour        | 0.0   &lt;-- default
 *      D    | perHour        | 1.0
 * </pre>
 */
class AlgorithmVariantTest
{
    private static Algorithm load(@TempDir Path tmp, String algorithmBlock) throws IOException
    {
        Path file = tmp.resolve("config.yaml");
        Files.writeString(file, """
            club:
              domain: "myc.org.au"
            algorithm:
            %s
            """.formatted(algorithmBlock));
        return JinxConfig.load(file).algorithm();
    }

    @Test
    void eachVariantExpandsToItsTwoKnobs()
    {
        assertThat(Variant.A.penaltyScaling(), equalTo(PenaltyScaling.FIXED));
        assertThat(Variant.A.givebackGamma(), closeTo(0.0, 1e-12));
        assertThat(Variant.B.penaltyScaling(), equalTo(PenaltyScaling.FIXED));
        assertThat(Variant.B.givebackGamma(), closeTo(1.0, 1e-12));
        assertThat(Variant.C.penaltyScaling(), equalTo(PenaltyScaling.PER_HOUR));
        assertThat(Variant.C.givebackGamma(), closeTo(0.0, 1e-12));
        assertThat(Variant.D.penaltyScaling(), equalTo(PenaltyScaling.PER_HOUR));
        assertThat(Variant.D.givebackGamma(), closeTo(1.0, 1e-12));
    }

    /**
     * The default is A — fixed penalties, shared evenly over everyone eligible.
     *
     * <p>It was B, fixed penalties shared by finish gap, back when γ was the only thing
     * keeping the winner from drawing its own penalty straight back. A boat in a penalty
     * place carries weight zero now, which does that job directly and for every penalised
     * place rather than only the one whose gap happened to be zero — so the proportional
     * weighting became an option rather than the default.
     */
    @Test
    void anAbsentAlgorithmBlockIsVariantA(@TempDir Path tmp) throws IOException
    {
        Path file = tmp.resolve("config.yaml");
        Files.writeString(file, "club:\n  domain: \"myc.org.au\"\n");
        Algorithm a = JinxConfig.load(file).algorithm();
        assertThat(a.penaltyScaling(), equalTo(PenaltyScaling.FIXED));
        assertThat(a.givebackGamma(), closeTo(0.0, 1e-12));
        assertThat(a.asVariant(), equalTo(java.util.Optional.of(Variant.A)));
    }

    @Test
    void anAlgorithmBlockWithNeitherVariantNorKnobsIsA(@TempDir Path tmp) throws IOException
    {
        Algorithm a = load(tmp, "  penaltyList: [5, 4, 3, 2, 1]");
        assertThat(a.penaltyScaling(), equalTo(PenaltyScaling.FIXED));
        assertThat(a.givebackGamma(), closeTo(0.0, 1e-12));
    }

    @Test
    void aVariantSetsBothKnobs(@TempDir Path tmp) throws IOException
    {
        Algorithm b = load(tmp, "  variant: B");
        assertThat(b.penaltyScaling(), equalTo(PenaltyScaling.FIXED));
        assertThat(b.givebackGamma(), closeTo(1.0, 1e-12));

        // Lower case and stray spacing are how a person writes it.
        Algorithm d = load(tmp, "  variant: \" d \"");
        assertThat(d.penaltyScaling(), equalTo(PenaltyScaling.PER_HOUR));
        assertThat(d.givebackGamma(), closeTo(1.0, 1e-12));
    }

    @Test
    void anExplicitKnobOverridesTheVariantItContradicts(@TempDir Path tmp) throws IOException
    {
        // A says fixed/0.0; the explicit gamma wins and only gamma is affected.
        Algorithm a = load(tmp, "  variant: A\n  givebackGamma: 0.4");
        assertThat(a.penaltyScaling(), equalTo(PenaltyScaling.FIXED));
        assertThat(a.givebackGamma(), closeTo(0.4, 1e-12));

        Algorithm c = load(tmp, "  variant: C\n  penaltyScaling: fixed");
        assertThat(c.penaltyScaling(), equalTo(PenaltyScaling.FIXED));
        assertThat(c.givebackGamma(), closeTo(0.0, 1e-12));
    }

    @Test
    void knobsAloneWorkWithoutAVariant(@TempDir Path tmp) throws IOException
    {
        Algorithm a = load(tmp, "  penaltyScaling: fixed\n  givebackGamma: 1.0");
        assertThat(a.penaltyScaling(), equalTo(PenaltyScaling.FIXED));
        assertThat(a.givebackGamma(), closeTo(1.0, 1e-12));
    }

    @Test
    void gammaIsContinuousBetweenTheCorners(@TempDir Path tmp) throws IOException
    {
        assertThat(load(tmp, "  givebackGamma: 0.35").givebackGamma(), closeTo(0.35, 1e-12));
    }

    @Test
    void anOutOfRangeGammaIsClampedAndAnUnreadableOneFallsBack(@TempDir Path tmp)
        throws IOException
    {
        // Gamma is a weighting exponent between "even" and "elapsed-weighted"; outside
        // that range it is not a stronger opinion, it is a mistake.
        assertThat(load(tmp, "  givebackGamma: 2.5").givebackGamma(), closeTo(1.0, 1e-12));
        assertThat(load(tmp, "  givebackGamma: -1").givebackGamma(), closeTo(0.0, 1e-12));
    }

    @Test
    void anUnreadableVariantOrScalingFallsBackToTheDefault(@TempDir Path tmp)
        throws IOException
    {
        Algorithm a = load(tmp, "  variant: Q");
        assertThat(a.penaltyScaling(), equalTo(PenaltyScaling.FIXED));
        assertThat(a.givebackGamma(), closeTo(0.0, 1e-12));

        assertThat(load(tmp, "  penaltyScaling: sideways").penaltyScaling(),
            equalTo(PenaltyScaling.FIXED));
    }

    @Test
    void perHourIsSpeltTheWayTheDocumentationSpellsIt(@TempDir Path tmp) throws IOException
    {
        assertThat(load(tmp, "  penaltyScaling: perHour").penaltyScaling(),
            equalTo(PenaltyScaling.PER_HOUR));
        assertThat(load(tmp, "  penaltyScaling: per_hour").penaltyScaling(),
            equalTo(PenaltyScaling.PER_HOUR));
        assertThat(load(tmp, "  penaltyScaling: PERHOUR").penaltyScaling(),
            equalTo(PenaltyScaling.PER_HOUR));
    }

    @Test
    void theTwoGivebackWeightsHaveDefaultsAndAreClamped(@TempDir Path tmp)
        throws IOException
    {
        // What the club modelled and adopted: a boat that ran out of time counts as 1.2
        // ordinary finishers, and one that never came counts as 0.2 of the share of the
        // fleet that stayed home.
        assertThat(load(tmp, "  penaltyList: [5]").dnfWeight(), closeTo(1.2, 1e-12));
        assertThat(load(tmp, "  penaltyList: [5]").dncWeight(), closeTo(0.2, 1e-12));
        assertThat(load(tmp, "  dnfWeight: 1.5").dnfWeight(), closeTo(1.5, 1e-12));
        assertThat(load(tmp, "  dncWeight: 0.5").dncWeight(), closeTo(0.5, 1e-12));

        // dncWeight scales a fraction that is already at most one, and the product is
        // what a boat that stayed home draws. Above one that boat could out-draw a boat
        // that came out and finished, which is the one thing it must never do. Clamped
        // rather than refused, like γ, so one bad character does not stop a race night.
        assertThat(load(tmp, "  dncWeight: 1.5").dncWeight(), closeTo(1.0, 1e-12));
        assertThat(load(tmp, "  dncWeight: -1").dncWeight(), closeTo(0.0, 1e-12));
        assertThat(load(tmp, "  dnfWeight: -1").dnfWeight(), closeTo(0.0, 1e-12));
    }

    @Test
    void theRetiredGivebackFleetAndDnfAllowanceKeysStillLoad(@TempDir Path tmp)
        throws IOException
    {
        // The club's config.yaml and its saved series overrides carry both of these
        // today. Retiring the settings must not stop a file loading — an unknown
        // property is ignored, not an error.
        JinxConfig.Algorithm a = load(tmp, "  givebackFleet: 0.33\n  dnfAllowance: 5");
        assertThat(a.dnfWeight(), closeTo(1.2, 1e-12));
        assertThat(a.dncWeight(), closeTo(0.2, 1e-12));
    }

    @Test
    void theRetiredInDurationSettingIsGoneAndOldFilesCarryingItStillLoad(@TempDir Path tmp)
        throws IOException
    {
        // The club's config.yaml has this key in it today. Removing the setting must not
        // stop the file loading — an unknown property is ignored, not an error.
        Algorithm a = load(tmp, "  penaltyList: [5]\n  dnfInRaceDuration: true");
        assertThat(a.penaltyList(), equalTo(List.of(5.0)));
    }

    @Test
    void theRetiredIdealRaceLengthIsIgnoredRatherThanFatal(@TempDir Path tmp) throws IOException
    {
        // Gamma used to be derived from it. It is a knob now, so the old key means
        // nothing — but a file carrying it must still load.
        Algorithm a = load(tmp, "  idealRaceLength: 75\n  idealRaceDuration: 75\n  variant: B");
        assertThat(a.penaltyScaling(), equalTo(PenaltyScaling.FIXED));
        assertThat(a.givebackGamma(), closeTo(1.0, 1e-12));
        assertThat(a.penaltyList(), equalTo(List.of(5.0, 4.0, 3.0, 2.0, 1.0)));
    }
}
