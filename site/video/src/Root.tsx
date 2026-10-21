import React from 'react';
import { Composition } from 'remotion';
import { Promo, PROMO_FRAMES } from './Promo';
import { areaSpec, bikeSpec, installSpec, Tutorial, type TutorialSpec } from './Tutorial';

// Each tutorial gets its own component: Remotion props must be JSON, and the steps contain JSX.
const make = (spec: TutorialSpec) => {
  const C: React.FC = () => <Tutorial spec={spec} />;
  return C;
};
const TutInstall = make(installSpec);
const TutArea = make(areaSpec);
const TutBike = make(bikeSpec);

export const Root: React.FC = () => (
  <>
    <Composition id="promo" component={Promo} durationInFrames={PROMO_FRAMES} fps={30} width={1920} height={1080} />
    <Composition id="tut-install" component={TutInstall} durationInFrames={installSpec.seconds * 30} fps={30} width={1920} height={1080} />
    <Composition id="tut-area" component={TutArea} durationInFrames={areaSpec.seconds * 30} fps={30} width={1920} height={1080} />
    <Composition id="tut-bike" component={TutBike} durationInFrames={bikeSpec.seconds * 30} fps={30} width={1920} height={1080} />
  </>
);
