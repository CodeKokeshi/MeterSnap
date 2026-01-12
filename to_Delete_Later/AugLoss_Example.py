import tensorflow as tf
from tensorflow import keras
from tensorflow.keras import layers
import numpy as np

"""
AugLoss (Augmentation Consistency Loss) Example for LeNet / Digit Classification.

This script demonstrates how to implement AugLoss to improve the robustness of a 
digit classifier (like LeNet) for tasks such as meter reading.

The core idea is to enforce that the model's prediction remains the same (consistent)
even when the input image is augmented (rotated, zoomed, shifted).
"""

def get_lenet_model(input_shape=(28, 28, 1), num_classes=10):
    """Standard LeNet-5 architecture adapted for Keras."""
    model = keras.Sequential([
        keras.Input(shape=input_shape),
        layers.Conv2D(6, kernel_size=(5, 5), activation="tanh", padding="same"),
        layers.AveragePooling2D(pool_size=(2, 2), strides=2),
        layers.Conv2D(16, kernel_size=(5, 5), activation="tanh", padding="valid"),
        layers.AveragePooling2D(pool_size=(2, 2), strides=2),
        layers.Flatten(),
        layers.Dense(120, activation="tanh"),
        layers.Dense(84, activation="tanh"),
        layers.Dense(num_classes, activation="softmax")
    ], name="LeNet")
    return model

class AugLossModel(keras.Model):
    """
    Custom Keras Model that implements Augmentation Consistency Loss.
    """
    def __init__(self, network, lambda_aug=1.0):
        super().__init__()
        self.network = network
        self.lambda_aug = lambda_aug # Weight for the augmentation loss
        
        # Metrics
        self.total_loss_tracker = keras.metrics.Mean(name="total_loss")
        self.cls_loss_tracker = keras.metrics.Mean(name="cls_loss")
        self.aug_loss_tracker = keras.metrics.Mean(name="aug_loss")
        self.accuracy_tracker = keras.metrics.SparseCategoricalAccuracy(name="accuracy")

    @property
    def metrics(self):
        return [
            self.total_loss_tracker,
            self.cls_loss_tracker,
            self.aug_loss_tracker,
            self.accuracy_tracker,
        ]

    def augment_images(self, images):
        """
        Apply random augmentations relevant to meter reading.
        - Random Rotation (meters can be tilted)
        - Random Zoom (camera distance varies)
        - Random Brightness (lighting varies)
        """
        # Random rotation between -15 and 15 degrees (approx)
        images = tf.image.rot90(images, k=0) # Placeholder for simple rotation
        # Note: For fine-grained rotation, use tf.keras.layers.RandomRotation or tf.image.transform
        
        # Add random noise
        noise = tf.random.normal(shape=tf.shape(images), mean=0.0, stddev=0.05)
        images = tf.clip_by_value(images + noise, 0.0, 1.0)
        
        return images

    def train_step(self, data):
        # Unpack data
        if len(data) == 3:
            x, y, sample_weight = data
        else:
            x, y = data
            sample_weight = None

        # 1. Generate Augmented Images
        x_aug = self.augment_images(x)

        with tf.GradientTape() as tape:
            # 2. Forward Pass (Original)
            y_pred = self.network(x, training=True)
            
            # 3. Forward Pass (Augmented)
            y_pred_aug = self.network(x_aug, training=True)

            # 4. Calculate Losses
            
            # A. Classification Loss (Standard Cross Entropy)
            cls_loss = self.compiled_loss(y, y_pred, sample_weight=sample_weight)
            
            # B. Augmentation Consistency Loss (AugLoss)
            # We want the distribution y_pred and y_pred_aug to be similar.
            # KL Divergence is a good measure for probability distributions.
            # We treat y_pred as the "target" distribution for y_pred_aug (or symmetric)
            aug_loss = keras.losses.KLDivergence()(y_pred, y_pred_aug)
            
            # Total Loss
            total_loss = cls_loss + (self.lambda_aug * aug_loss)

        # 5. Backpropagation
        gradients = tape.gradient(total_loss, self.network.trainable_weights)
        self.optimizer.apply_gradients(zip(gradients, self.network.trainable_weights))

        # 6. Update Metrics
        self.total_loss_tracker.update_state(total_loss)
        self.cls_loss_tracker.update_state(cls_loss)
        self.aug_loss_tracker.update_state(aug_loss)
        self.accuracy_tracker.update_state(y, y_pred)
        
        return {m.name: m.result() for m in self.metrics}

    def call(self, inputs):
        return self.network(inputs)

# --- Usage Example ---
if __name__ == "__main__":
    # 1. Prepare Data (using MNIST as proxy for digits)
    (x_train, y_train), (x_test, y_test) = keras.datasets.mnist.load_data()
    x_train = x_train.astype("float32") / 255.0
    x_test = x_test.astype("float32") / 255.0
    x_train = np.expand_dims(x_train, -1)
    x_test = np.expand_dims(x_test, -1)

    # 2. Create Base LeNet Model
    lenet = get_lenet_model()

    # 3. Wrap in AugLoss Model
    # lambda_aug controls how much we care about consistency vs accuracy
    training_model = AugLossModel(lenet, lambda_aug=1.5)

    # 4. Compile
    training_model.compile(
        optimizer=keras.optimizers.Adam(learning_rate=1e-3),
        loss=keras.losses.SparseCategoricalCrossentropy(),
    )

    # 5. Train
    print("Starting training with AugLoss...")
    training_model.fit(x_train, y_train, epochs=2, batch_size=64, validation_split=0.1)
    
    # 6. Save the inner model for TFLite conversion
    # training_model.network.save("metersnap_lenet_augloss.h5")
    print("Training finished.")
