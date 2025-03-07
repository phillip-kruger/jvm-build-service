// Package v1alpha1 contains API Schema definitions for the hacbs jvmbuildservice v1alpha1 API group
// +k8s:deepcopy-gen=package,register
// +groupName=jvmbuildservice.io
package v1alpha1

import (
	metav1 "k8s.io/apimachinery/pkg/apis/meta/v1"
	"k8s.io/apimachinery/pkg/runtime"
	"k8s.io/apimachinery/pkg/runtime/schema"
)

// SchemeGroupVersion is group version used to register these objects
var SchemeGroupVersion = schema.GroupVersion{Group: "jvmbuildservice.io", Version: "v1alpha1"}

// Kind takes an unqualified kind and returns back a Group qualified GroupKind
func Kind(kind string) schema.GroupKind {
	return SchemeGroupVersion.WithKind(kind).GroupKind()
}

// Resource takes an unqualified resource and returns a Group qualified GroupResource
func Resource(resource string) schema.GroupResource {
	return SchemeGroupVersion.WithResource(resource).GroupResource()
}

var (
	SchemeBuilder = runtime.NewSchemeBuilder(addKnownTypes)

	// AddToScheme adds Build types to the scheme.
	AddToScheme = SchemeBuilder.AddToScheme
)

// Adds the list of known types to Scheme.
func addKnownTypes(scheme *runtime.Scheme) error {
	scheme.AddKnownTypes(SchemeGroupVersion,
		&ArtifactBuild{},
		&ArtifactBuildList{},
		&DependencyBuild{},
		&DependencyBuildList{},
		&TektonWrapper{},
		&TektonWrapperList{},
		&SystemConfig{},
		&SystemConfigList{},
		&UserConfig{},
		&UserConfigList{},
		&RebuiltArtifact{},
		&RebuiltArtifactList{},
	)
	// &Condition{},
	// &ConditionList{},

	metav1.AddToGroupVersion(scheme, SchemeGroupVersion)
	return nil
}
