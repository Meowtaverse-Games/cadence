import FamilyControls

extension AuthorizationStatus {
    var isApprovedForCadence: Bool {
        // The newer status includes ordinary Screen Time authorization. Cadence still requests
        // only .individual and never requests or reads non-tokenized activity data.
        if #available(iOS 26.4, *), self == .approvedWithDataAccess { return true }
        return self == .approved
    }
}
