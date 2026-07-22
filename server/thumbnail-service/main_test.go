package main

import (
	"testing"
	"time"
)

func TestThumbnailSeek(t *testing.T) {
	cases := []struct {
		name     string
		duration time.Duration
		want     time.Duration
	}{
		{"long video uses five minutes", time.Hour, 5 * time.Minute},
		{"short video uses thirty five percent", 4 * time.Minute, 84 * time.Second},
		{"unknown video uses safe fallback", 0, 30 * time.Second},
	}
	for _, tc := range cases {
		t.Run(tc.name, func(t *testing.T) {
			if got := thumbnailSeek(tc.duration); got != tc.want {
				t.Fatalf("thumbnailSeek(%v) = %v, want %v", tc.duration, got, tc.want)
			}
		})
	}
}

func TestEncodedResourcePath(t *testing.T) {
	got := encodedResourcePath("/media/My Videos/a+b.mp4")
	want := "/api/resources/media/My%20Videos/a+b.mp4"
	if got != want {
		t.Fatalf("encodedResourcePath = %q, want %q", got, want)
	}
}
