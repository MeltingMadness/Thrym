import XCTest
@testable import ThrymIOS

/// When the first-start introduction appears: once on a fresh install, never with training data.
final class OnboardingTests: XCTestCase {
    func testFreshInstallShowsOnboarding() {
        let data = ILTrainingData(exercises: [ILExercise(id: 1, name: "Bankdrücken")])
        XCTAssertTrue(IOSOnboarding.shouldShow(completed: false, data: data))
    }

    func testCompletedOnboardingStaysHidden() {
        XCTAssertFalse(IOSOnboarding.shouldShow(completed: true, data: ILTrainingData()))
    }

    func testExistingWorkoutsOrPlansSkipOnboarding() {
        let withSession = ILTrainingData(workoutSessions: [ILWorkoutSession(id: 1, startTime: 0, endTime: 100)])
        let withPlan = ILTrainingData(trainingPlans: [ILTrainingPlan(id: 1, name: "Push")])
        XCTAssertFalse(IOSOnboarding.shouldShow(completed: false, data: withSession))
        XCTAssertFalse(IOSOnboarding.shouldShow(completed: false, data: withPlan))
    }

    func testStepsEndWithStart() {
        XCTAssertEqual(IOSOnboardingStep.allCases.first, .welcome)
        XCTAssertEqual(IOSOnboardingStep.allCases.last, .start)
    }
}
